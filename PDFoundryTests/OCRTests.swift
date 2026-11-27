import PDFKit
import Testing
import UIKit
@testable import PDFoundry

/// Make Searchable: Vision reads pages that have no text, and invisible text is laid over the words.
@MainActor
struct OCRTests {
    private let folder: URL

    init() throws {
        folder = try MarkupFixture.folder("OCRTests")
    }

    /// A picture of `text`, as a scanner makes: no text layer.
    private static func picture(of text: String) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 2
        return UIGraphicsImageRenderer(size: CGSize(width: 612, height: 792), format: format).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 612, height: 792))
            let style = NSMutableParagraphStyle()
            style.alignment = .center
            (text as NSString).draw(in: CGRect(x: 40, y: 300, width: 532, height: 120),
                                    withAttributes: [.font: UIFont.systemFont(ofSize: 56, weight: .semibold), .paragraphStyle: style,
                                                     .foregroundColor: UIColor.black])
        }
    }

    /// Page 1: a picture of "Invoice total". Page 2: real text. Page 3: a picture of "فاتورة".
    private func mixedFile(named name: String = "Scan.pdf") throws -> URL {
        let data = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792)).pdfData { context in
            context.beginPage()
            Self.picture(of: "Invoice total").draw(in: CGRect(x: 0, y: 0, width: 612, height: 792))
            context.beginPage()
            ("Typed page with real text" as NSString).draw(at: CGPoint(x: 72, y: 100), withAttributes: [.font: UIFont.systemFont(ofSize: 20)])
            context.beginPage()
            Self.picture(of: "فاتورة").draw(in: CGRect(x: 0, y: 0, width: 612, height: 792))
        }
        return try MarkupFixture.write(data, named: name, in: folder)
    }

    private func found(_ text: String, in document: PDFDocument?) -> Bool {
        !(document?.findString(text, withOptions: .caseInsensitive).isEmpty ?? true)
    }

    /// The page is drawn for reading away from the main thread with Core Graphics; it must match what the reader shows.
    @Test func pageDrawnOffTheMainThreadMatchesTheReader() async throws {
        let crop = CGRect(x: 30, y: 50, width: 520, height: 690)
        let data = MarkupFixture.textPDF(["Turned and cropped words"], rotation: 90, crop: crop)
        let page = try #require(PDFDocument(data: data)?.page(at: 0))
        let source = try #require(OCR.PageSource(page))
        let expected = try #require(PageImage.image(of: page, dpi: 72))
        let drawn = try #require(await Task.detached { OCR.render(source, dpi: 72) }.value)
        #expect(drawn.width == expected.width && drawn.height == expected.height)
        #expect(MarkupFixture.difference(drawn, expected) < 0.01)
        #expect(MarkupFixture.darkCount(drawn) > 50)
    }

    @Test func pagesWithoutTextAreFound() throws {
        let document = try #require(PDFDocument(url: try mixedFile()))
        #expect(OCR.pagesNeedingText(document) == [0, 2])
    }

    @Test func englishWordsBecomeFindableAndThePageLooksTheSame() async throws {
        let url = try mixedFile()
        let (model, _) = await MarkupFixture.open(url)
        let document = try #require(model.document)
        let firstPage = try #require(document.page(at: 0))
        let before = try #require(PageImage.image(of: firstPage, dpi: 72))
        let typed = document.page(at: 1)

        var steps: [Int] = []
        let count = try await model.makeSearchable { done, total in
            steps.append(done)
            #expect(total == 2)
        }
        #expect(count >= 1)
        #expect(steps.last == 2)
        #expect(document.page(at: 1) === typed, "a page that has text is left alone")
        #expect(found("Invoice", in: model.document))

        let saved = PDFDocument(url: url)
        #expect(found("Invoice", in: saved))
        #expect(found("total", in: saved))
        let savedFirst = try #require(saved?.page(at: 0))
        let after = try #require(PageImage.image(of: savedFirst, dpi: 72))
        #expect(MarkupFixture.difference(before, after) <= 0.01, "the page looks the same")

        // Undo puts the original pages back. (Their text can't be checked: once PDFKit has drawn an image-only page,
        // it runs its own text recognition on it in memory, so the original page object reports text too.)
        model.undo()
        #expect(model.document?.page(at: 0) === firstPage, "the original page is back")
        #expect(model.document?.pageCount == 3)
        #expect(!model.canUndo)
    }

    @Test(.enabled(if: OCR.readsArabic, "Vision on this system has no Arabic text recognition"))
    func arabicWordBecomesFindable() async throws {
        let url = try mixedFile()
        let (model, _) = await MarkupFixture.open(url)
        _ = try await model.makeSearchable { _, _ in }
        #expect(found("فاتورة", in: PDFDocument(url: url)))
    }

    @Test func ocrCancelChangesNothing() async throws {
        let url = try mixedFile()
        let original = try Data(contentsOf: url)
        let (model, _) = await MarkupFixture.open(url)
        let pages = (0..<3).map { model.document?.page(at: $0) }

        let task = Task { @MainActor in
            try await model.makeSearchable { done, _ in
                if done >= 1 { withUnsafeCurrentTask { $0?.cancel() } }
            }
        }
        await #expect(throws: CancellationError.self) { try await task.value }
        #expect(try Data(contentsOf: url) == original)
        #expect((0..<3).map { model.document?.page(at: $0) }.elementsEqual(pages) { $0 === $1 })
        #expect(!model.canUndo)
    }

    @Test func ocrKeepsPassword() async throws {
        let source = try mixedFile(named: "Plain.pdf")
        let url = folder.appending(path: "Protected.pdf")
        let document = try #require(PDFDocument(url: source))
        #expect(document.write(to: url, withOptions: PDFOperations.writeOptions(password: "scan-pw")))
        let (model, _) = await MarkupFixture.open(url, password: "scan-pw")
        #expect(model.canEdit)
        _ = try await model.makeSearchable { _, _ in }

        let saved = try #require(PDFDocument(url: url))
        #expect(saved.isLocked)
        #expect(saved.unlock(withPassword: "scan-pw"))
        #expect(found("Invoice", in: saved))
    }

    @Test func nothingFoundChangesNothing() async throws {
        let url = try MarkupFixture.write(MarkupFixture.textPDF([""]), named: "Empty.pdf", in: folder)
        let original = try Data(contentsOf: url)
        let (model, _) = await MarkupFixture.open(url)
        #expect(OCR.pagesNeedingText(try #require(model.document)) == [0])
        let count = try await model.makeSearchable { _, _ in }
        #expect(count == 0)
        #expect(try Data(contentsOf: url) == original)
        #expect(OCR.nothingFoundMessage == "No text was found on these pages.")
    }
}
