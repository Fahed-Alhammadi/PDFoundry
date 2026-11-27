import PDFKit
import Testing
import UIKit
@testable import PDFoundry

/// Fixes from the review of the Fill & Sign, Split and Password-protect work.
@MainActor
struct ReviewFixTests {
    private let folder: URL
    private let library: URL
    private let elsewhere: URL

    init() throws {
        folder = FileManager.default.temporaryDirectory.appending(path: "ReviewFixTests-\(UUID().uuidString)")
        library = folder.appending(path: "Library")
        elsewhere = folder.appending(path: "Elsewhere")
        try FileManager.default.createDirectory(at: library, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: elsewhere, withIntermediateDirectories: true)
    }

    private func makeStore() -> DocumentStore {
        DocumentStore(directory: library, linksFile: folder.appending(path: "opened.json"), copyRoots: [])
    }

    private func plainData(pages: Int = 1) -> Data {
        UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792)).pdfData { context in
            for index in 0..<pages {
                context.beginPage()
                ("Page \(index + 1)" as NSString).draw(at: CGPoint(x: 72, y: 72), withAttributes: [.font: UIFont.systemFont(ofSize: 24)])
            }
        }
    }

    private func open(_ url: URL, password: String? = nil) async throws -> ReaderModel {
        let model = ReaderModel(url: url)
        await model.load()
        if let password { #expect(model.unlock(with: password)) }
        let view = PDFView(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        view.autoScales = true
        view.document = model.document
        view.layoutIfNeeded()
        model.pdfView = view
        return model
    }

    private func placeStamp(_ model: ReaderModel) {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 200, height: 80)).image { context in
            UIColor.black.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 200, height: 80))
        }
        model.beginPlacing(signature: image)
        model.placement?.rect = CGRect(x: 100, y: 300, width: 150, height: 60)
        model.commitPlacement()
    }

    private func stamps(_ document: PDFDocument) -> Int {
        document.page(at: 0)?.annotations.filter { $0.type == "Stamp" }.count ?? 0
    }

    // MARK: 1. Protect from Tools while the file is open in the reader

    @Test func protectingAFileOpenInTheReaderKeepsItProtected() async throws {
        let store = makeStore()
        let lease = elsewhere.appending(path: "Lease.pdf")
        try plainData().write(to: lease)
        try await store.open([lease])
        let model = try await open(lease)
        store.attach(model)

        try await store.protect(lease, password: "pw")

        #expect(model.isProtected, "the open reader takes the password")
        placeStamp(model)
        model.syncFields()
        let reopened = try #require(PDFDocument(url: lease))
        #expect(reopened.isLocked, "the reader's next save keeps the password")
        #expect(reopened.unlock(withPassword: "pw"))
        #expect(stamps(reopened) == 1)
    }

    // MARK: 2. A write that fails halfway

    @Test func aFailedWriteLeavesThePreviousFile() throws {
        let url = elsewhere.appending(path: "Secret.pdf")
        let plain = try #require(PDFDocument(data: plainData()))
        #expect(plain.write(to: url, withOptions: PDFOperations.writeOptions(password: "pw")))
        let before = try Data(contentsOf: url)
        let edited = try #require(PDFDocument(data: plainData(pages: 2)))

        struct DiskFull: Error {}
        let written = PDFOperations.writeInPlace(edited, to: url, password: "pw", atomic: false) { data, target in
            // Cut short: half the bytes reach the disk, then the disk is full.
            try data.prefix(data.count / 2).write(to: target)
            throw DiskFull()
        }

        #expect(!written)
        #expect(try Data(contentsOf: url) == before, "the file is still the protected file it was")
    }

    @Test func libraryFilesAreWrittenAtomically() throws {
        let url = library.appending(path: "Doc.pdf")
        try plainData().write(to: url)
        let document = try #require(PDFDocument(data: plainData(pages: 3)))
        var usedAtomic = false
        #expect(PDFOperations.writeInPlace(document, to: url, password: nil, atomic: true) { data, target in
            usedAtomic = true
            try data.write(to: target, options: .atomic)
        })
        #expect(usedAtomic)
        #expect(PDFDocument(url: url)?.pageCount == 3)
        #expect(PDFOperations.isInsideFolder(url, URL.documentsDirectory) == false)
        #expect(PDFOperations.isInsideFolder(URL.documentsDirectory.appending(path: "A.pdf"), URL.documentsDirectory))
    }

    // MARK: 4. Owner password different from the user password

    @Test func editingAsOwnerSaysThePasswordWillChange() async throws {
        let url = elsewhere.appending(path: "Owned.pdf")
        let plain = try #require(PDFDocument(data: plainData()))
        #expect(plain.write(to: url, withOptions: [.userPasswordOption: "user", .ownerPasswordOption: "owner"]))

        let model = try await open(url, password: "owner")
        #expect(model.canEdit)
        #expect(model.hint == ReaderModel.passwordNotice)
        placeStamp(model)

        let reopened = try #require(PDFDocument(url: url))
        #expect(!reopened.unlock(withPassword: "user"), "the separate user password no longer opens it")
        #expect(reopened.unlock(withPassword: "owner"))
    }

    // MARK: 5. Remove Password keeps links

    @Test func removingThePasswordKeepsFields() async throws {
        let form = elsewhere.appending(path: "Form.pdf")
        try FormFixture.write(to: form, password: "pw")

        let model = try await open(form, password: "pw")
        #expect(model.removePassword())

        let plain = try #require(PDFDocument(url: form))
        #expect(!plain.isEncrypted)
        let names = Set(plain.page(at: 0)?.annotations.compactMap(\.fieldName) ?? [])
        #expect(names == FormFixture.fieldNames, "every field is still a field")
        #expect(try FormFieldTests.appearanceLength(ofField: "id", in: form) > 0, "with its appearance")
    }

    // MARK: Minor

    @Test func aSplitThatFailsLeavesNoParts() async throws {
        let store = makeStore()
        let source = elsewhere.appending(path: "Report.pdf")
        try plainData(pages: 4).write(to: source)

        await #expect(throws: PDFoundryError.self) {
            // The second part asks for a page that isn't there.
            _ = try await store.split(source, into: [[0, 1], [9]], password: nil)
        }
        #expect(try FileManager.default.contentsOfDirectory(atPath: library.path).isEmpty)
        #expect(store.items.isEmpty)
    }

    @Test func revertAfterProtectingKeepsThePassword() async throws {
        let url = elsewhere.appending(path: "Plain.pdf")
        try plainData(pages: 2).write(to: url)
        let model = try await open(url)
        placeStamp(model)
        #expect(model.protect(with: "pw"))

        #expect(model.revertToOriginal())

        #expect(model.isProtected)
        let reopened = try #require(PDFDocument(url: url))
        #expect(reopened.isLocked, "reverting doesn't take the new password off")
        #expect(reopened.unlock(withPassword: "pw"))
        #expect(stamps(reopened) == 0 && reopened.pageCount == 2, "the content is the original")
    }

    @Test func mergeRefusesARestrictionOnlySource() throws {
        let plain = try #require(PDFDocument(data: plainData()))
        let restricted = try #require(plain.dataRepresentation(options: [PDFDocumentWriteOption.ownerPasswordOption: "o", PDFDocumentWriteOption.userPasswordOption: "", PDFDocumentWriteOption.accessPermissionsOption: NSNumber(value: 0)]))
        #expect(throws: PDFoundryError.protectedSource("Restricted")) {
            _ = try PDFOperations.merge([(name: "Restricted", data: restricted), (name: "Plain", data: plainData())])
        }
    }

    @Test func undoingAFieldKeepsItsReadOnlyFlag() async throws {
        let url = elsewhere.appending(path: "Form.pdf")
        try FormFixture.write(to: url)
        let model = try await open(url)
        let name = try #require(model.document?.page(at: 0)?.annotations.first { $0.fieldName == "name" })
        name.widgetStringValue = "Jane"
        model.syncFields()
        name.isReadOnly = true // e.g. a form script marked it read-only meanwhile
        model.undo()
        #expect(name.isReadOnly)
        #expect(name.widgetStringValue == "")
    }

    @Test func aNewPlacementDoesNotLandOnTheLastOne() async throws {
        let model = try await open(try {
            let url = elsewhere.appending(path: "Sign.pdf")
            try plainData().write(to: url)
            return url
        }())
        let size = CGSize(width: 390, height: 800)
        let centred = PlacementOverlay.startRect(aspect: 2.5, in: size, avoiding: nil)
        #expect(abs(centred.midX - 195) < 1 && abs(centred.midY - 400) < 1)
        model.beginPlacing(signature: UIGraphicsImageRenderer(size: CGSize(width: 250, height: 100)).image { _ in })
        model.placement?.rect = centred
        model.commitPlacement()
        let last = try #require(model.lastPlacedRect)
        #expect(last == centred)
        let next = PlacementOverlay.startRect(aspect: 2.5, in: size, avoiding: last)
        #expect(!next.intersects(last), "the second signature starts beside the first, not on it")
        #expect(CGRect(origin: .zero, size: size).contains(next))
    }
}
