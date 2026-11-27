import PDFKit
import Testing
import UIKit
@testable import PDFoundry

/// Shapes (rectangle, ellipse, line, arrow) and comments are real PDF annotations.
@MainActor
struct ShapeCommentTests {
    private let folder: URL

    init() throws {
        folder = try MarkupFixture.folder("ShapeCommentTests")
    }

    private func blankFile() throws -> URL {
        try MarkupFixture.write(MarkupFixture.textPDF([""]), named: "Plan.pdf", in: folder)
    }

    private func components(_ color: UIColor) -> [CGFloat] {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        color.getRed(&r, green: &g, blue: &b, alpha: &a)
        return [r, g, b]
    }

    @Test func shapesAreRealAnnotations() async throws {
        let url = try blankFile()
        let (model, view) = await MarkupFixture.open(url)
        let page = try #require(model.document?.page(at: 0))
        let frame = view.convert(page.bounds(for: .cropBox), from: page)

        let drags: [(ShapeKind, CGPoint, CGPoint)] = [
            (.rectangle, CGPoint(x: 40, y: 40), CGPoint(x: 140, y: 100)),
            (.ellipse, CGPoint(x: 200, y: 40), CGPoint(x: 300, y: 110)),
            (.line, CGPoint(x: 40, y: 200), CGPoint(x: 200, y: 260)),
            (.arrow, CGPoint(x: 220, y: 300), CGPoint(x: 330, y: 200)),
        ]
        for (kind, from, to) in drags {
            model.beginShape(kind, color: Mark.ink)
            #expect(model.mode == .shaping)
            model.commitShape(from: CGPoint(x: frame.minX + from.x, y: frame.minY + from.y),
                              to: CGPoint(x: frame.minX + to.x, y: frame.minY + to.y))
            #expect(model.mode == .shaping, "the tool stays on to draw the next one")
        }
        model.cancelMode()
        #expect(page.annotations.count == 4)

        let reloaded = try #require(PDFDocument(url: url)?.page(at: 0))
        let marks = reloaded.annotations
        #expect(marks.compactMap(\.type) == ["Square", "Circle", "Line", "Line"])
        #expect(marks.allSatisfy(Annotations.isOurs))
        #expect(marks.allSatisfy(Annotations.isMovable))
        for mark in marks {
            #expect(mark.border?.lineWidth == 2)
            let rgb = components(mark.color)
            let ink = components(Mark.ink)
            #expect(zip(rgb, ink).allSatisfy { abs($0 - $1) < 0.02 }, "graphite stroke")
        }
        #expect(marks[2].endLineStyle == PDFLineStyle.none)
        #expect(marks[3].endLineStyle == .openArrow)
        #expect(marks[3].startLineStyle == PDFLineStyle.none)

        // Drawn where they were dragged: the line's middle and the rectangle's edge are dark.
        let image = try #require(PageImage.image(of: reloaded, dpi: 72))
        let pixels = MarkupFixture.rgba(image)
        func isDark(near point: CGPoint) -> Bool {
            // `point` is in the view; the page frame in the view is scaled from page points.
            let scale = CGFloat(image.width) / frame.width
            let cx = Int(point.x * scale), cy = Int(point.y * scale)
            for dy in -3...3 { for dx in -3...3 {
                let x = cx + dx, y = cy + dy
                guard x >= 0, y >= 0, x < image.width, y < image.height else { continue }
                let o = (y * image.width + x) * 4
                if pixels[o] < 100 && pixels[o + 1] < 100 && pixels[o + 2] < 100 { return true }
            } }
            return false
        }
        #expect(isDark(near: CGPoint(x: 120, y: 230)), "the line's middle")
        #expect(isDark(near: CGPoint(x: 40, y: 70)), "the rectangle's left edge")
        #expect(!isDark(near: CGPoint(x: 90, y: 70)), "no fill")
    }

    @Test func shapesTakeTheChosenColour() async throws {
        let url = try blankFile()
        let (model, view) = await MarkupFixture.open(url)
        let page = try #require(model.document?.page(at: 0))
        let frame = view.convert(page.bounds(for: .cropBox), from: page)
        let blue = try #require(ShapeKind.colors.first { $0.name == "Blue" }).color
        model.beginShape(.rectangle, color: blue)
        model.commitShape(from: CGPoint(x: frame.minX + 40, y: frame.minY + 40), to: CGPoint(x: frame.minX + 120, y: frame.minY + 90))
        let saved = try #require(PDFDocument(url: url)?.page(at: 0)?.annotations.first)
        #expect(zip(components(saved.color), components(blue)).allSatisfy { abs($0 - $1) < 0.02 })
        #expect(ShapeKind.colors.map(\.name) == ["Graphite", "Yellow", "Green", "Pink", "Blue"])
    }

    @Test func commentRoundTripIncludingArabic() async throws {
        let url = try blankFile()
        let (model, view) = await MarkupFixture.open(url)
        let page = try #require(model.document?.page(at: 0))
        let frame = view.convert(page.bounds(for: .cropBox), from: page)
        let text = "مرحبا بالعالم"

        let comment = try #require(model.addComment(at: CGPoint(x: frame.midX, y: frame.minY + 80), text: text))
        #expect(comment.type == "Text")
        let reloaded = try #require(PDFDocument(url: url)?.page(at: 0)?.annotations.first)
        #expect(reloaded.type == "Text")
        #expect(reloaded.contents == text)
        #expect(reloaded.iconType == .comment)
        #expect(Annotations.isOurs(reloaded))

        model.updateComment(comment, text: "Check the totals")
        #expect(PDFDocument(url: url)?.page(at: 0)?.annotations.first?.contents == "Check the totals")
        model.undo()
        #expect(comment.contents == text)
        #expect(PDFDocument(url: url)?.page(at: 0)?.annotations.first?.contents == text)
    }

    @Test func emptyCommentIsNotCreated() async throws {
        let url = try blankFile()
        let (model, view) = await MarkupFixture.open(url)
        let page = try #require(model.document?.page(at: 0))
        let frame = view.convert(page.bounds(for: .cropBox), from: page)

        #expect(model.addComment(at: CGPoint(x: frame.midX, y: frame.midY), text: "  \n ") == nil)
        #expect(page.annotations.isEmpty)
        #expect(!model.canUndo)

        let comment = try #require(model.addComment(at: CGPoint(x: frame.midX, y: frame.midY), text: "Note"))
        model.updateComment(comment, text: "")
        #expect(page.annotations.isEmpty, "emptying a comment deletes it")
        #expect(PDFDocument(url: url)?.page(at: 0)?.annotations.isEmpty == true)
    }
}
