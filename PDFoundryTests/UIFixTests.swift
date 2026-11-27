import PDFKit
import Testing
import UIKit
@testable import PDFoundry

/// Fixes found in the screenshot run: shape hint wording, stamp sharpness, highlight colour after reload.
@MainActor
struct UIFixTests {
    private let folder: URL

    init() throws {
        folder = try MarkupFixture.folder("UIFixTests")
    }

    @Test func shapeHintUsesTheRightArticle() {
        #expect(ShapeKind.arrow.drawHint == "Drag on the page to draw an arrow. Scroll with two fingers.")
        #expect(ShapeKind.ellipse.drawHint == "Drag on the page to draw an ellipse. Scroll with two fingers.")
        #expect(ShapeKind.rectangle.drawHint == "Drag on the page to draw a rectangle. Scroll with two fingers.")
        #expect(ShapeKind.line.drawHint == "Drag on the page to draw a line. Scroll with two fingers.")
    }

    /// The widths of every image written into the file.
    private func imageWidths(_ data: Data) -> [Int] {
        let text = String(decoding: data, as: UTF8.self)
        let expression = try! NSRegularExpression(pattern: #"/Subtype\s*/Image[^>]*?/Width\s+(\d+)|/Width\s+(\d+)[^>]*?/Subtype\s*/Image"#)
        return expression.matches(in: text, range: NSRange(text.startIndex..., in: text)).compactMap { match in
            for group in 1...2 {
                if let range = Range(match.range(at: group), in: text) { return Int(text[range]) }
            }
            return nil
        }
    }

    @Test func placedSignatureKeepsItsResolutionInTheFile() async throws {
        let url = try MarkupFixture.write(MarkupFixture.textPDF([""]), named: "Sign.pdf", in: folder)
        let (model, _) = await MarkupFixture.open(url)
        // A signature as the pad makes it: 3 pixels per point.
        let format = UIGraphicsImageRendererFormat()
        format.scale = 3
        let signature = UIGraphicsImageRenderer(size: CGSize(width: 300, height: 100), format: format).image { context in
            UIColor.black.setStroke()
            let path = UIBezierPath()
            path.move(to: CGPoint(x: 10, y: 80))
            path.addCurve(to: CGPoint(x: 290, y: 20), controlPoint1: CGPoint(x: 100, y: -40), controlPoint2: CGPoint(x: 200, y: 140))
            path.lineWidth = 3
            path.stroke()
        }
        MarkupFixture.place(signature, in: model, at: CGRect(x: 80, y: 200, width: 150, height: 50))
        let saved = try Data(contentsOf: url)
        let widths = imageWidths(saved)
        // Viewers smooth the picture when it is drawn smaller or larger than its pixels.
        #expect(String(decoding: saved, as: UTF8.self).contains("/Interpolate true"))
        #expect(!widths.isEmpty)
        // At least 4 pixels per point of the placed width, so it stays sharp when zoomed.
        #expect((widths.max() ?? 0) >= 150 * 4, "image widths in the file: \(widths)")
    }

    /// The mean colour inside `rect` (page space, unrotated page drawn at 72 dpi).
    private func meanColour(_ image: CGImage, in rect: CGRect, pageHeight: CGFloat) -> [Double] {
        let pixels = MarkupFixture.rgba(image)
        var sums = [0.0, 0.0, 0.0], count = 0.0
        for y in Int(pageHeight - rect.maxY)..<Int(pageHeight - rect.minY) {
            for x in Int(rect.minX)..<Int(rect.maxX) {
                let o = (y * image.width + x) * 4
                for c in 0..<3 { sums[c] += Double(pixels[o + c]) }
                count += 1
            }
        }
        return sums.map { $0 / max(count, 1) }
    }

    @Test func highlightLooksTheSameAfterReopening() async throws {
        // PDFKit writes a highlight's colour without its transparency: the colours are solid.
        #expect(ModeBar.highlights.allSatisfy { $0.color.cgColor.alpha == 1 })

        let url = try MarkupFixture.write(MarkupFixture.textPDF(["Quarterly report for the board"]), named: "Mark.pdf", in: folder)
        let (model, view) = await MarkupFixture.open(url)
        let page = try #require(model.document?.page(at: 0))
        MarkupFixture.select("Quarterly", on: page, in: view, model: model)
        model.markup(.highlight, color: ModeBar.highlights[0].color)
        let mark = try #require(page.annotations.first).bounds.insetBy(dx: 8, dy: 6)
        let first = try #require(PageImage.image(of: page, dpi: 72))
        let reloaded = try #require(PDFDocument(url: url)?.page(at: 0))
        let again = try #require(PageImage.image(of: reloaded, dpi: 72))
        let before = meanColour(first, in: mark, pageHeight: 792), after = meanColour(again, in: mark, pageHeight: 792)
        let worst = zip(before, after).map { abs($0 - $1) }.max() ?? 0
        #expect(worst <= 6, "mean colour \(before) then \(after)")
    }

}
