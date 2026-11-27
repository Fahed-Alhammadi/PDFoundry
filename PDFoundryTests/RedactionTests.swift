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
        // And written as hex strings (<5345…>), as some writers do.
        let hex = [needle, wide].flatMap { bytes -> [Data] in
            let upper = bytes.map { String(format: "%02X", $0) }.joined()
            return [Data(upper.utf8), Data(upper.lowercased().utf8)]
        }
        return everything(in: data).contains { part in
            ([needle, wide] + hex).contains { part.range(of: $0) != nil }
        }
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

    /// A tagged form with the secret in every place a page's words can hide: the page text, a marked-content
    /// /ActualText, a structure element's /Alt and /ActualText, a field value whose widget is on the page, /CO, XFA,
    /// a comment, plus an outline entry and a named destination that point at the page.
    static func hidingPlaces() -> Data {
        let o = RawPDF.object
        let page = "BT /F1 24 Tf 90 700 Td (Cover) Tj ET"
        let secretPage = "/Span << /MCID 0 /ActualText (SECRET-MC) >> BDC BT /F1 24 Tf 90 700 Td (SECRET-PAGE) Tj ET EMC"
        return RawPDF.build([
            1: o("<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [20 0 R] /CO [20 0 R] /XFA 30 0 R /DA (/Helv 12 Tf 0 g) /DR << /Font << /Helv 9 0 R >> >> >> /StructTreeRoot 40 0 R /MarkInfo << /Marked true >> /Outlines 50 0 R /Names << /Dests << /Names [(chap2) [4 0 R /Fit]] >> >> >>"),
            2: o("<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >>"),
            3: o("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 6 0 R /Resources << /Font << /F1 9 0 R >> >> >>"),
            4: o("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 7 0 R /Resources << /Font << /F1 9 0 R >> >> /Annots [21 0 R 22 0 R] /StructParents 0 >>"),
            5: o("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 8 0 R /Resources << /Font << /F1 9 0 R >> >> >>"),
            6: RawPDF.stream("", Data(page.utf8)),
            7: RawPDF.stream("", Data(secretPage.utf8)),
            8: RawPDF.stream("", Data("BT /F1 24 Tf 90 700 Td (Last) Tj ET".utf8)),
            9: o("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"),
            20: o("<< /FT /Tx /T (acct) /V (SECRET-FIELD) /DA (/Helv 12 Tf 0 g) /Kids [21 0 R] >>"),
            21: o("<< /Type /Annot /Subtype /Widget /Parent 20 0 R /Rect [90 600 300 620] /P 4 0 R /F 4 >>"),
            22: o("<< /Type /Annot /Subtype /Text /Rect [400 600 424 624] /Contents (SECRET-NOTE) /P 4 0 R >>"),
            30: RawPDF.stream("", Data("<xdp:xdp><field>SECRET-XFA</field></xdp:xdp>".utf8)),
            40: o("<< /Type /StructTreeRoot /K 41 0 R /ParentTree 43 0 R >>"),
            41: o("<< /Type /StructElem /S /Document /P 40 0 R /K [42 0 R] >>"),
            42: o("<< /Type /StructElem /S /P /P 41 0 R /Pg 4 0 R /K 0 /Alt (SECRET-ALT) /ActualText (SECRET-ACTUAL) >>"),
            43: o("<< /Nums [0 [42 0 R]] >>"),
            50: o("<< /Type /Outlines /First 51 0 R /Last 51 0 R /Count 1 >>"),
            51: o("<< /Title (Chapter two) /Parent 50 0 R /Dest [4 0 R /Fit] >>"),
        ])
    }

    @Test func nothingOfARedactedPageIsLeftAnywhereInTheFile() async throws {
        let url = try MarkupFixture.write(Self.hidingPlaces(), named: "Tagged.pdf", in: folder)
        let places = ["SECRET-PAGE", "SECRET-MC", "SECRET-ALT", "SECRET-ACTUAL", "SECRET-FIELD", "SECRET-XFA", "SECRET-NOTE"]
        let before = try Data(contentsOf: url)
        for place in places { #expect(Self.contains(place, inFile: before), "the fixture has \(place)") }

        let (model, _) = await MarkupFixture.open(url)
        model.addRedaction(CGRect(x: 0, y: 0, width: 612, height: 792), page: 1)
        model.applyRedactions()

        let data = try Data(contentsOf: url)
        for place in places { #expect(!Self.contains(place, inFile: data), "\(place) is gone") }
        #expect(!Self.contains("SECRET", inFile: data))
        let saved = try #require(PDFDocument(data: data))
        #expect(saved.pageCount == 3)
        #expect(saved.page(at: 0)?.string?.contains("Cover") == true)
        #expect(saved.page(at: 2)?.string?.contains("Last") == true)
    }

    @Test func largePagesAreRenderedAtALowerResolution() throws {
        #expect(Redaction.dpi(for: CGSize(width: 612, height: 792)) == 300)
        let a0 = CGSize(width: 2384, height: 3370)
        let dpi = Redaction.dpi(for: a0)
        #expect(dpi < 300)
        let pixels = (a0.width * dpi / 72) * (a0.height * dpi / 72)
        #expect(pixels <= Redaction.maximumPixels)

        let data = MarkupFixture.textPDF(["Huge \(Self.secret) poster"], size: a0)
        let document = try #require(PDFDocument(data: data))
        let page = try #require(document.page(at: 0))
        let redacted = try #require(Redaction.redactedPage(page, boxes: [CGRect(x: 80, y: 3200, width: 800, height: 60)]))
        #expect(redacted.bounds(for: .mediaBox).size == a0)
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
