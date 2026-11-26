import PDFKit
import Testing
import UIKit
@testable import PDFoundry

@MainActor
struct PDFOperationsTests {
    private let folder: URL

    init() throws {
        folder = FileManager.default.temporaryDirectory.appending(path: "PDFoundryTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    private func makePDF(named name: String, pages: Int) throws -> URL {
        let rect = CGRect(x: 0, y: 0, width: 612, height: 792)
        let data = UIGraphicsPDFRenderer(bounds: rect).pdfData { context in
            for index in 0..<pages {
                context.beginPage()
                ("\(name) page \(index + 1)" as NSString).draw(at: CGPoint(x: 72, y: 72), withAttributes: [.font: UIFont.systemFont(ofSize: 24)])
            }
        }
        let url = folder.appending(path: "\(name).pdf")
        try data.write(to: url)
        return url
    }

    @Test func mergeKeepsEveryPageInOrder() throws {
        let first = try makePDF(named: "Alpha", pages: 2)
        let second = try makePDF(named: "Beta", pages: 3)

        let merged = try PDFOperations.merge([first, second])

        #expect(merged.pageCount == 5)
        #expect(merged.page(at: 0)?.string?.contains("Alpha page 1") == true)
        #expect(merged.page(at: 2)?.string?.contains("Beta page 1") == true)
        #expect(merged.page(at: 4)?.string?.contains("Beta page 3") == true)
    }

    @Test func mergeRejectsUnreadableFiles() throws {
        let bogus = folder.appending(path: "Broken.pdf")
        try Data("not a pdf".utf8).write(to: bogus)

        #expect(throws: PDFoundryError.self) {
            _ = try PDFOperations.merge([bogus])
        }
    }

    @Test func extractCopiesOnlyChosenPages() throws {
        let url = try makePDF(named: "Source", pages: 4)
        let document = try #require(PDFDocument(url: url))

        let extracted = try PDFOperations.extract([3, 1], from: document)

        #expect(extracted.pageCount == 2)
        #expect(extracted.page(at: 0)?.string?.contains("page 2") == true)
        #expect(extracted.page(at: 1)?.string?.contains("page 4") == true)
        #expect(document.pageCount == 4)
    }

    @Test func signatureStampSurvivesSavingAndReloading() throws {
        let url = try makePDF(named: "Contract", pages: 1)
        let document = try #require(PDFDocument(url: url))
        let page = try #require(document.page(at: 0))

        let signature = UIGraphicsImageRenderer(size: CGSize(width: 200, height: 80)).image { context in
            UIColor.red.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 200, height: 80))
        }
        // Lower-left quarter of the page, in PDF coordinates (origin bottom-left).
        PDFOperations.stamp(signature, on: page, bounds: CGRect(x: 100, y: 100, width: 200, height: 80))
        #expect(document.write(to: url))

        let reloaded = try #require(PDFDocument(url: url))
        let reloadedPage = try #require(reloaded.page(at: 0))
        #expect(reloadedPage.annotations.contains { $0.type == "Stamp" })

        // Render at 1 point per pixel and sample the middle of the stamp.
        let rendered = reloadedPage.thumbnail(of: CGSize(width: 612, height: 792), for: .mediaBox)
        let pixel = try #require(rendered.pixel(atFraction: CGPoint(x: 200.0 / 612.0, y: (792.0 - 140.0) / 792.0)))
        #expect(pixel.red > 200 && pixel.green < 80 && pixel.blue < 80, "The stamped image should be painted into the saved page")
    }

    @Test func aboutShowsTheFullWebsiteAddress() {
        #expect(AppInfo.websiteDisplay == "fahed-alhammadi.github.io/PDFoundry")
    }

    @Test func openingTheSameFileAgainDoesNotAddItAgain() async throws {
        let library = folder.appending(path: "Library")
        try FileManager.default.createDirectory(at: library, withIntermediateDirectories: true)
        let store = DocumentStore(directory: library)
        let report = try makePDF(named: "Report", pages: 2)

        let first = try await store.importFiles([report])
        let again = try await store.importFiles([report])
        #expect(again == first)
        #expect(store.items.count == 1)

        // The same contents under another name (as Mail or Files hand them over) are still the same document.
        let renamedCopy = folder.appending(path: "Report from Mail.pdf")
        try FileManager.default.copyItem(at: report, to: renamedCopy)
        #expect(try await store.importFiles([renamedCopy, report]) == first)
        #expect(store.items.count == 1)

        // A different document with the same name is added beside it.
        try FileManager.default.removeItem(at: report)
        let other = try makePDF(named: "Report", pages: 3)
        let added = try await store.importFiles([other])
        #expect(added.first?.lastPathComponent == "Report 2.pdf")
        #expect(store.items.count == 2)
    }

    /// A library whose own folder, list of opened files and "copy instead" rule are all inside the test folder.
    private func makeStore() throws -> (store: DocumentStore, library: URL, elsewhere: URL) {
        let library = folder.appending(path: "Library")
        let elsewhere = folder.appending(path: "Elsewhere")
        try FileManager.default.createDirectory(at: library, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: elsewhere, withIntermediateDirectories: true)
        // No folder counts as a temporary hand-over here, so files in "Elsewhere" are opened in place.
        return (DocumentStore(directory: library, linksFile: folder.appending(path: "opened.json"), copyRoots: []), library, elsewhere)
    }

    private func libraryFiles(_ library: URL) throws -> [String] {
        try FileManager.default.contentsOfDirectory(atPath: library.path).sorted()
    }

    @Test func aFileFromElsewhereIsOpenedInPlaceAndNotCopied() async throws {
        let (store, library, elsewhere) = try makeStore()
        let contract = elsewhere.appending(path: "Contract.pdf")
        try FileManager.default.moveItem(at: try makePDF(named: "Contract", pages: 2), to: contract)

        let opened = try await store.open([contract])
        try await store.open([contract]) // opening it again

        #expect(opened.map(\.standardizedFileURL) == [contract.standardizedFileURL])
        #expect(try libraryFiles(library).isEmpty, "nothing is copied into PDFoundry")
        #expect(store.items.count == 1)
        let item = try #require(store.items.first)
        #expect(item.isExternal && item.pageCount == 2)
        #expect(item.location == "Files › Elsewhere")

        // The list survives a restart: a new store reads the same bookmarks.
        let again = DocumentStore(directory: library, linksFile: folder.appending(path: "opened.json"), copyRoots: [])
        await again.refresh()
        #expect(again.items.map(\.name) == ["Contract"])
    }

    /// Found on the simulator: a refresh that started before a file was opened wiped that file from the list
    /// when it finished afterwards.
    @Test func aFileOpenedWhileTheListIsRefreshingIsNotLost() async throws {
        let (store, _, elsewhere) = try makeStore()
        var files: [URL] = []
        for index in 1...4 {
            let file = elsewhere.appending(path: "File \(index).pdf")
            try FileManager.default.moveItem(at: try makePDF(named: "File \(index)", pages: 1), to: file)
            files.append(file)
        }
        for file in files {
            async let slowRefresh: Void = store.refresh()
            try await store.open([file])
            await slowRefresh
        }
        await store.refresh()
        #expect(store.items.map(\.name).sorted() == ["File 1", "File 2", "File 3", "File 4"])
    }

    @Test func changesAreSavedToTheSameFileStraightAway() async throws {
        let (store, library, elsewhere) = try makeStore()
        let contract = elsewhere.appending(path: "Contract.pdf")
        try FileManager.default.moveItem(at: try makePDF(named: "Contract", pages: 2), to: contract)
        try await store.open([contract])

        let model = ReaderModel(url: contract)
        await model.load()
        #expect(!model.canRevert)
        try #require(model.document).page(at: 0)?.rotation = 90
        model.pagesDidChange()

        #expect(PDFDocument(url: contract)?.page(at: 0)?.rotation == 90, "the change is in the same file without a Save step")
        #expect(model.canRevert)
        #expect(try libraryFiles(library).isEmpty, "nothing is copied into PDFoundry")
        #expect(try FileManager.default.contentsOfDirectory(atPath: elsewhere.path) == ["Contract.pdf"], "no stray files beside it")
    }

    @Test func sharingAPasswordProtectedFileKeepsItsPassword() async throws {
        let (_, _, elsewhere) = try makeStore()
        let secret = elsewhere.appending(path: "Secret.pdf")
        let plain = try #require(PDFDocument(url: try makePDF(named: "Secret", pages: 1)))
        #expect(plain.write(to: secret, withOptions: [.userPasswordOption: "open-me", .ownerPasswordOption: "open-me"]))

        let model = ReaderModel(url: secret)
        await model.load()
        #expect(model.isLocked)
        #expect(model.unlock(with: "open-me"))

        let shared = try #require(model.temporaryCopy())
        let copy = try #require(PDFDocument(url: shared))
        #expect(copy.isLocked, "the shared copy still asks for the password")
        #expect(try Data(contentsOf: shared) == Data(contentsOf: secret))
    }

    @Test func revertPutsTheFileBackExactlyAsItWasOpened() async throws {
        let (_, _, elsewhere) = try makeStore()
        let contract = elsewhere.appending(path: "Contract.pdf")
        try FileManager.default.moveItem(at: try makePDF(named: "Contract", pages: 3), to: contract)
        let before = try Data(contentsOf: contract)

        let model = ReaderModel(url: contract)
        await model.load()
        let document = try #require(model.document)
        document.page(at: 0)?.rotation = 180
        document.removePage(at: 2) // a deleted page cannot be brought back any other way
        model.pagesDidChange()
        #expect(try Data(contentsOf: contract) != before)
        #expect(PDFDocument(url: contract)?.pageCount == 2)

        #expect(model.revertToOriginal())

        #expect(try Data(contentsOf: contract) == before, "the file is byte for byte what it was")
        #expect(model.pageCount == 3 && model.document?.page(at: 0)?.rotation == 0)
        #expect(!model.canRevert && !model.canUndo)
        #expect(!model.revertToOriginal(), "nothing left to revert")
    }

    @Test func removingAnOpenedFileNeverDeletesIt() async throws {
        let (store, library, elsewhere) = try makeStore()
        let contract = elsewhere.appending(path: "Contract.pdf")
        try FileManager.default.moveItem(at: try makePDF(named: "Contract", pages: 1), to: contract)
        let kept = try await store.importFiles([try makePDF(named: "Kept", pages: 1)])
        try await store.open([contract])
        #expect(store.items.count == 2)

        // "Delete" on a mixed selection deletes PDFoundry's own document and only forgets the other file.
        await store.delete(store.items)

        #expect(store.items.isEmpty)
        #expect(FileManager.default.fileExists(atPath: contract.path), "the file opened in place is still there")
        #expect(!FileManager.default.fileExists(atPath: kept[0].path))
        #expect(try libraryFiles(library).isEmpty)
    }

    @Test func aFileDeletedElsewhereDropsOffTheList() async throws {
        let (store, _, elsewhere) = try makeStore()
        let contract = elsewhere.appending(path: "Contract.pdf")
        try FileManager.default.moveItem(at: try makePDF(named: "Contract", pages: 1), to: contract)
        try await store.open([contract])
        try FileManager.default.removeItem(at: contract)

        await store.refresh()
        #expect(store.items.isEmpty)
    }

    @Test func temporaryHandOversAreStillCopiedAndCanBeSavedIntoPDFoundry() async throws {
        let library = folder.appending(path: "Library")
        try FileManager.default.createDirectory(at: library, withIntermediateDirectories: true)
        // Here the whole test folder counts as temporary, like a file shared from Mail.
        let store = DocumentStore(directory: library, linksFile: folder.appending(path: "opened.json"), copyRoots: [folder])
        let attachment = try makePDF(named: "Attachment", pages: 1)

        let opened = try await store.open([attachment])
        #expect(opened.first?.deletingLastPathComponent().standardizedFileURL == library.standardizedFileURL)
        #expect(store.items.count == 1 && store.items[0].isExternal == false)

        #expect(DocumentStore.location(of: URL(filePath: "/private/var/mobile/Library/Mobile Documents/com~apple~CloudDocs/Contracts/Lease.pdf")) == "iCloud Drive › Contracts")
        #expect(DocumentStore.location(of: URL(filePath: "/private/var/mobile/Library/Mobile Documents/com~apple~CloudDocs/Lease.pdf")) == "iCloud Drive")
    }

    @Test func libraryNamesNeverCollideOrEscapeTheFolder() async throws {
        let store = DocumentStore(directory: folder)
        let first = try makePDF(named: "Report", pages: 1)

        #expect(store.uniqueURL(named: "Report").lastPathComponent == "Report 2.pdf")
        #expect(store.uniqueURL(named: "../../etc/passwd").deletingLastPathComponent().standardizedFileURL == folder.standardizedFileURL)
        #expect(DocumentStore.sanitized("  Invoice.pdf ") == "Invoice")

        await store.refresh()
        let item = try #require(store.items.first { $0.url.lastPathComponent == first.lastPathComponent })
        #expect(item.pageCount == 1)

        try await store.duplicate(item)
        #expect(store.items.count == 2)

        let merged = try await store.merge(store.items, named: "Both", deleteOriginals: true)
        #expect(store.items.map(\.url.lastPathComponent) == [merged.lastPathComponent])
        #expect(store.items.first?.pageCount == 2)
    }
}

private extension UIImage {
    /// Reads one pixel; `fraction` is measured from the top-left corner.
    func pixel(atFraction fraction: CGPoint) -> (red: UInt8, green: UInt8, blue: UInt8)? {
        guard let cgImage else { return nil }
        var data = [UInt8](repeating: 0, count: 4)
        guard let context = CGContext(data: &data, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        let x = CGFloat(cgImage.width) * fraction.x
        let y = CGFloat(cgImage.height) * fraction.y
        // Core Graphics counts rows from the bottom; shift the image so the wanted pixel lands at the origin.
        context.draw(cgImage, in: CGRect(x: -x, y: y - CGFloat(cgImage.height), width: CGFloat(cgImage.width), height: CGFloat(cgImage.height)))
        return (data[0], data[1], data[2])
    }
}
