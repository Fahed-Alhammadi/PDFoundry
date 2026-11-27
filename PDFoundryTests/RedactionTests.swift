import Foundation
import PDFKit
import Testing
import UIKit
@testable import PDFoundry

/// Redaction removes what is under the boxes for good: from the text, from every stream in the file, from marks
/// and form fields. Other pages stay as they were.
@MainActor
struct RedactionTests {
    private let folder: URL
    private static let secret = "ZEPHYRQUOTA"

    init() throws {
        folder = try MarkupFixture.folder("RedactionTests")
    }

    private func threePages() throws -> URL {
        try MarkupFixture.write(MarkupFixture.textPDF(["Cover page one", "Account \(Self.secret) listed", "Closing page three"]),
                                named: "Accounts.pdf", in: folder)
    }

    /// The box around `word` on `page`, in page space.
    private func box(around word: String, on page: PDFPage) throws -> CGRect {
        let text = try #require(page.string)
        let range = try #require(text.range(of: word))
        let selection = try #require(page.selection(for: NSRange(range, in: text)))
        return selection.bounds(for: page).insetBy(dx: -2, dy: -2)
    }

    /// Every stream in the file, inflated where it is Flate-compressed, and the file's own bytes.
    static func everything(in data: Data) -> [Data] {
        var parts = [data]
        let bytes = [UInt8](data)
        let start = Array("stream".utf8), end = Array("endstream".utf8)
        var index = 0
        while let found = find(start, in: bytes, from: index) {
            var begin = found + start.count
            // "endstream" also contains "stream".
            if found >= 3, Array(bytes[(found - 3)..<found]) == Array("end".utf8) { index = begin; continue }
            if begin < bytes.count, bytes[begin] == 0x0D { begin += 1 }
            if begin < bytes.count, bytes[begin] == 0x0A { begin += 1 }
            guard let stop = find(end, in: bytes, from: begin) else { break }
            let body = Data(bytes[begin..<stop])
            parts.append(body)
            if let inflated = inflate(body) { parts.append(inflated) }
            index = stop + end.count
        }
        return parts
    }

    private static func find(_ needle: [UInt8], in haystack: [UInt8], from start: Int) -> Int? {
        guard needle.count <= haystack.count, start <= haystack.count - needle.count else { return nil }
        var index = start
        while index <= haystack.count - needle.count {
            if haystack[index] == needle[0], Array(haystack[index..<(index + needle.count)]) == needle { return index }
            index += 1
        }
        return nil
    }

    /// zlib data (a 2-byte header, then deflate) inflated, or nil.
    private static func inflate(_ data: Data) -> Data? {
        guard data.count > 2 else { return nil }
        return try? (data.dropFirst(2) as NSData).decompressed(using: .zlib) as Data
    }

    static func contains(_ word: String, inFile data: Data) -> Bool {
        let needle = Data(word.utf8)
        // Text written as UTF-16 (annotation contents, field values) is looked for too.
        let wide = word.data(using: .utf16BigEndian)!
        return everything(in: data).contains { $0.range(of: needle) != nil || $0.range(of: wide) != nil }
    }

    @Test func theScannerFindsTheWordBeforeRedaction() throws {
        let url = try threePages()
        #expect(Self.contains(Self.secret, inFile: try Data(contentsOf: url)))
        // Also once PDFKit has written the file (it compresses streams): the check below is not vacuous.
        let document = try #require(PDFDocument(url: url))
        let written = try #require(document.dataRepresentation())
        #expect(Self.contains(Self.secret, inFile: written))
    }

    @Test func redactedWordIsGoneFromTextAndEveryStream() async throws {
        let url = try threePages()
        let (model, view) = await MarkupFixture.open(url)
        let document = try #require(model.document)
        let before = (0..<3).map { document.page(at: $0)?.string ?? "" }
        let page = try #require(document.page(at: 1))
        // A comment on the page with the word in it goes too.
        let frame = view.convert(page.bounds(for: .cropBox), from: page)
        model.addComment(at: CGPoint(x: frame.midX, y: frame.midY), text: "Remember \(Self.secret)")

        model.addRedaction(try box(around: Self.secret, on: page), page: 1)
        #expect(model.pendingRedactionCount == 1)
        #expect(!Self.contains("Redact", inFile: try Data(contentsOf: url)), "pending boxes are never written")
        model.applyRedactions()
        #expect(model.pendingRedactionCount == 0)

        let data = try Data(contentsOf: url)
        let saved = try #require(PDFDocument(data: data))
        // (a) text extraction
        #expect(!Self.mentions(saved.string))
        #expect(saved.findString(Self.secret, withOptions: .caseInsensitive).isEmpty)
        // (b) every stream, inflated
        #expect(!Self.contains(Self.secret, inFile: data))
        // (c) the other pages are untouched
        #expect(saved.page(at: 0)?.string == before[0])
        #expect(saved.page(at: 2)?.string == before[2])
        #expect(saved.pageCount == 3)
        #expect(saved.page(at: 1)?.annotations.isEmpty == true)
        #expect((saved.page(at: 1)?.string ?? "").isEmpty, "the redacted page is an image")
    }

    @Test func redactionRemovesFormFieldValues() async throws {
        let url = folder.appending(path: "Form.pdf")
        try FormFixture.write(to: url)
        do {
            let document = try #require(PDFDocument(url: url))
            let name = try #require(FormFields.widgets(in: document).first { $0.fieldName == "name" })
            _ = FormFields.set(name, value: .text(Self.secret))
            #expect(document.write(to: url))
            #expect(Self.contains(Self.secret, inFile: try Data(contentsOf: url)))
        }
        let (model, _) = await MarkupFixture.open(url)
        let page = try #require(model.document?.page(at: 0))
        model.addRedaction(CGRect(x: 60, y: 670, width: 320, height: 40), page: 0)
        model.applyRedactions()
        _ = page

        let data = try Data(contentsOf: url)
        #expect(!Self.contains(Self.secret, inFile: data), "no field value is left in the file")
        let saved = try #require(PDFDocument(data: data))
        #expect(FormFields.widgets(in: saved).isEmpty)
    }

    @Test func redactedPageKeepsSizeAndRotation() async throws {
        let crop = CGRect(x: 30, y: 40, width: 540, height: 700)
        let url = try MarkupFixture.write(MarkupFixture.textPDF(["Turned \(Self.secret) page"], rotation: 90, crop: crop),
                                          named: "Turned.pdf", in: folder)
        let (model, _) = await MarkupFixture.open(url)
        let page = try #require(model.document?.page(at: 0))
        let shownBefore = try #require(PageImage.image(of: page, dpi: 72))
        let mark = try box(around: "page", on: page)
        model.addRedaction(try box(around: Self.secret, on: page), page: 0)
        model.applyRedactions()

        let saved = try #require(PDFDocument(url: url)?.page(at: 0))
        #expect(saved.rotation == 90)
        #expect(saved.bounds(for: .mediaBox) == CGRect(x: 0, y: 0, width: 612, height: 792))
        #expect(saved.bounds(for: .cropBox) == crop)
        let shownAfter = try #require(PageImage.image(of: saved, dpi: 72))
        #expect(shownAfter.width == shownBefore.width && shownAfter.height == shownBefore.height)
        // The words left of the box look the same, the box is black.
        #expect(MarkupFixture.difference(shownBefore, shownAfter) < 0.03)
        let blackCentre = try #require(MarkupFixture.darkCentre(shownAfter, threshold: 20))
        let secretBox = try box(around: Self.secret, on: page)
        let expected = MarkupFixture.displayedFraction(of: CGPoint(x: secretBox.midX, y: secretBox.midY), on: saved)
        #expect(abs(blackCentre.x - expected.x) < 0.03 && abs(blackCentre.y - expected.y) < 0.03)
        _ = mark
    }

    @Test func redactionKeepsPassword() async throws {
        let url = folder.appending(path: "Locked.pdf")
        let plain = try #require(PDFDocument(data: MarkupFixture.textPDF(["Locked \(Self.secret) words"])))
        #expect(plain.write(to: url, withOptions: PDFOperations.writeOptions(password: "pass-1")))
        let (model, _) = await MarkupFixture.open(url, password: "pass-1")
        #expect(model.canEdit)
        let page = try #require(model.document?.page(at: 0))
        model.addRedaction(try box(around: Self.secret, on: page), page: 0)
        model.applyRedactions()

        let saved = try #require(PDFDocument(url: url))
        #expect(saved.isEncrypted && saved.isLocked)
        #expect(!saved.unlock(withPassword: "wrong"))
        #expect(saved.unlock(withPassword: "pass-1"))
        #expect(!Self.mentions(saved.string))
    }

    @Test func undoRedactionRestoresText() async throws {
        let url = try threePages()
        let (model, _) = await MarkupFixture.open(url)
        let page = try #require(model.document?.page(at: 1))
        model.addRedaction(try box(around: Self.secret, on: page), page: 1)
        model.applyRedactions()
        #expect(model.canUndo)
        #expect(!Self.mentions(model.document?.page(at: 1)?.string))
        #expect(!Self.mentions(PDFDocument(url: url)?.string))

        model.undo()
        #expect(Self.mentions(model.document?.page(at: 1)?.string))
        #expect(Self.mentions(PDFDocument(url: url)?.page(at: 1)?.string))
    }

    /// Whether `text` has the secret word. (Kept out of `#expect`, whose expansion of `contains` on an optional
    /// chain misreports.)
    static func mentions(_ text: String?) -> Bool {
        guard let text else { return false }
        return text.range(of: secret) != nil
    }

    @Test func discardingLeavesTheFileAlone() async throws {
        let url = try threePages()
        let original = try Data(contentsOf: url)
        let (model, _) = await MarkupFixture.open(url)
        model.addRedaction(CGRect(x: 10, y: 10, width: 100, height: 40), page: 2)
        model.discardRedactions()
        #expect(model.pendingRedactionCount == 0)
        #expect(try Data(contentsOf: url) == original)
    }
}
