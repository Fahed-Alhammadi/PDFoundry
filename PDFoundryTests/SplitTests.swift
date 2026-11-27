import PDFKit
import Testing
import UIKit
@testable import PDFoundry

/// Split PDF: page ranges, and a split that writes new documents and never touches the source.
@MainActor
struct SplitTests {
    private let folder: URL
    private let library: URL
    private let elsewhere: URL

    init() throws {
        folder = FileManager.default.temporaryDirectory.appending(path: "SplitTests-\(UUID().uuidString)")
        library = folder.appending(path: "Library")
        elsewhere = folder.appending(path: "Elsewhere")
        try FileManager.default.createDirectory(at: library, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: elsewhere, withIntermediateDirectories: true)
    }

    private func makeStore() -> DocumentStore {
        DocumentStore(directory: library, linksFile: folder.appending(path: "opened.json"), copyRoots: [])
    }

    private func makePDF(named name: String, pages: Int, in directory: URL, password: String? = nil) throws -> URL {
        let data = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792)).pdfData { context in
            for index in 0..<pages {
                context.beginPage()
                ("\(name) page \(index + 1)" as NSString).draw(at: CGPoint(x: 72, y: 72), withAttributes: [.font: UIFont.systemFont(ofSize: 24)])
            }
        }
        let url = directory.appending(path: "\(name).pdf")
        if let password {
            let document = try #require(PDFDocument(data: data))
            #expect(document.write(to: url, withOptions: PDFOperations.writeOptions(password: password)))
        } else {
            try data.write(to: url)
        }
        return url
    }

    // MARK: Ranges (the shared vectors)

    @Test func customRangesBecomeZeroBasedParts() throws {
        #expect(try PageRanges.parse("1-3, 4-6, 9", pageCount: 10).get() == [[0, 1, 2], [3, 4, 5], [8]])
        #expect(try PageRanges.parse("2", pageCount: 2).get() == [[1]])
        #expect(try PageRanges.parse(" 1 - 2 ,2-3 ", pageCount: 3).get() == [[0, 1], [1, 2]], "spaces ignored, overlaps allowed")
    }

    @Test func badRangesExplainWhatIsWrong() {
        func message(_ text: String, _ count: Int) -> String? {
            if case .failure(let error) = PageRanges.parse(text, pageCount: count) { return error.localizedDescription }
            return nil
        }
        #expect(message("", 10) == "Enter page ranges, such as 1-3, 4-6.")
        #expect(message("   ", 10) == "Enter page ranges, such as 1-3, 4-6.")
        #expect(message("0", 10) == "Page 0 doesn't exist. This PDF has 10 pages.")
        #expect(message("1-11", 10) == "Page 11 doesn't exist. This PDF has 10 pages.")
        #expect(message("5-3", 10) == "Write ranges from low to high, such as 3-5.")
        #expect(message("a", 10) == "Use numbers, dashes and commas, such as 1-3, 4-6.")
        #expect(message("1-2-3", 10) == "Use numbers, dashes and commas, such as 1-3, 4-6.")
    }

    @Test func everyNMakesConsecutiveChunks() {
        #expect(PageRanges.every(2, pageCount: 5) == [[0, 1], [2, 3], [4]])
        #expect(PageRanges.every(1, pageCount: 3) == [[0], [1], [2]])
        #expect(PageRanges.every(9, pageCount: 3) == [[0, 1, 2]])
        #expect(PageRanges.every(0, pageCount: 3) == [[0], [1], [2]], "n is at least 1")
    }

    // MARK: Split

    @Test func splitWritesPartsAndLeavesSourceUnchanged() async throws {
        let store = makeStore()
        let source = try makePDF(named: "Report", pages: 4, in: elsewhere)
        let before = try Data(contentsOf: source)

        let parts = try await store.split(source, into: [[0, 1], [2, 3]], password: nil)

        #expect(parts.map(\.name) == ["Report (part 1)", "Report (part 2)"])
        #expect(parts.map(\.pageCount) == [2, 2])
        #expect(try Data(contentsOf: source) == before, "the source is byte for byte what it was")
        #expect(try FileManager.default.contentsOfDirectory(atPath: elsewhere.path) == ["Report.pdf"], "nothing written beside it")
        #expect(!store.items.contains { $0.url.standardizedFileURL == source.standardizedFileURL }, "the source is not listed")
        #expect(parts.allSatisfy { $0.url.deletingLastPathComponent().standardizedFileURL == library.standardizedFileURL })
        let second = try #require(PDFDocument(url: parts[1].url))
        #expect(second.page(at: 0)?.string?.contains("Report page 3") == true)
    }

    @Test func splitNamesNeverOverwriteExistingDocuments() async throws {
        let store = makeStore()
        let source = try makePDF(named: "Report", pages: 2, in: elsewhere)
        _ = try makePDF(named: "Report (part 1)", pages: 1, in: library)

        let parts = try await store.split(source, into: PageRanges.every(1, pageCount: 2), password: nil)

        #expect(parts.map(\.name) == ["Report (part 1) 2", "Report (part 2)"])
    }

    @Test func splitOfProtectedFileKeepsPassword() async throws {
        let store = makeStore()
        let source = try makePDF(named: "Secret", pages: 3, in: elsewhere, password: "open-me")

        await #expect(throws: PDFoundryError.self) {
            _ = try await store.split(source, into: [[0], [1, 2]], password: nil)
        }
        await #expect(throws: PDFoundryError.self) {
            _ = try await store.split(source, into: [[0], [1, 2]], password: "wrong")
        }

        let parts = try await store.split(source, into: [[0], [1, 2]], password: "open-me")

        #expect(parts.count == 2)
        for part in parts {
            let document = try #require(PDFDocument(url: part.url))
            #expect(document.isEncrypted && document.isLocked, "each part still asks for the password")
            #expect(document.unlock(withPassword: "open-me"))
        }
        let second = try #require(PDFDocument(url: parts[1].url))
        #expect(second.unlock(withPassword: "open-me"))
        #expect(second.pageCount == 2)
    }
}
