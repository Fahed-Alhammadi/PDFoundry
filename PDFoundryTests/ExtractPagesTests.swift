import PDFKit
import Testing
import UIKit
@testable import PDFoundry

/// Extract Pages on the Tools tab copies pages into a new document and never touches the source.
@MainActor
struct ExtractPagesTests {
    private let folder: URL
    private let library: URL
    private let elsewhere: URL

    init() throws {
        folder = FileManager.default.temporaryDirectory.appending(path: "ExtractPagesTests-\(UUID().uuidString)")
        library = folder.appending(path: "Library")
        elsewhere = folder.appending(path: "Elsewhere")
        try FileManager.default.createDirectory(at: library, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: elsewhere, withIntermediateDirectories: true)
    }

    private func makeStore() -> DocumentStore {
        DocumentStore(directory: library, linksFile: folder.appending(path: "opened.json"), copyRoots: [])
    }

    private func makePDF(named name: String, pages: Int, in directory: URL) throws -> URL {
        let data = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792)).pdfData { context in
            for index in 0..<pages {
                context.beginPage()
                ("\(name) page \(index + 1)" as NSString).draw(at: CGPoint(x: 72, y: 72), withAttributes: [.font: UIFont.systemFont(ofSize: 24)])
            }
        }
        let url = directory.appending(path: "\(name).pdf")
        try data.write(to: url)
        return url
    }

    @Test func extractingFromAFileOpenedInPlaceLeavesItUnchanged() async throws {
        let store = makeStore()
        let contract = try makePDF(named: "Contract", pages: 4, in: elsewhere)
        try await store.open([contract])
        let before = try Data(contentsOf: contract)
        let modified = try contract.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate

        let item = try await store.extractPages(from: contract, pages: [0, 2])

        #expect(try Data(contentsOf: contract) == before, "the source is byte for byte what it was")
        #expect(try contract.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate == modified)
        #expect(try FileManager.default.contentsOfDirectory(atPath: elsewhere.path) == ["Contract.pdf"], "nothing written beside it")
        #expect(item.pageCount == 2)
        #expect(item.name == "Contract (pages)")
        #expect(item.url.deletingLastPathComponent().standardizedFileURL == library.standardizedFileURL)
        #expect(!item.isExternal)
        let extracted = try #require(PDFDocument(url: item.url))
        #expect(extracted.page(at: 0)?.string?.contains("Contract page 1") == true)
        #expect(extracted.page(at: 1)?.string?.contains("Contract page 3") == true)
    }

    @Test func extractedDocumentKeepsPageOrder() async throws {
        let store = makeStore()
        let report = try makePDF(named: "Report", pages: 5, in: library)
        await store.refresh()

        let item = try await store.extractPages(from: report, pages: [4, 1, 3])

        let extracted = try #require(PDFDocument(url: item.url))
        #expect(extracted.pageCount == 3)
        #expect(extracted.page(at: 0)?.string?.contains("Report page 2") == true)
        #expect(extracted.page(at: 1)?.string?.contains("Report page 4") == true)
        #expect(extracted.page(at: 2)?.string?.contains("Report page 5") == true)
        #expect(PDFDocument(url: report)?.pageCount == 5)
    }

    @Test func aFilePickedInFilesIsNotAddedToTheLibrary() async throws {
        let store = makeStore()
        let picked = try makePDF(named: "Picked", pages: 2, in: elsewhere)

        let item = try await store.extractPages(from: picked, pages: [1])

        #expect(store.items.map(\.url.standardizedFileURL) == [item.url.standardizedFileURL], "only the new document is listed")
        #expect(store.items.allSatisfy { !$0.isExternal }, "the picked file is not opened in place")
    }

    @Test func nothingIsSavedWithoutPages() async throws {
        let store = makeStore()
        let report = try makePDF(named: "Report", pages: 2, in: library)
        await store.refresh()

        await #expect(throws: PDFoundryError.self) {
            _ = try await store.extractPages(from: report, pages: [])
        }
        #expect(try FileManager.default.contentsOfDirectory(atPath: library.path) == ["Report.pdf"])
    }
}
