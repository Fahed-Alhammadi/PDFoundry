import PDFKit
import PencilKit
import Testing
import UIKit
@testable import PDFoundry

/// Marks made in PDFoundry are tagged, and can be selected later to move, resize or delete them.
///
/// PDFKit check (iOS 26/27 simulator and macOS): a custom key set with `setValue(_:forAnnotationKey:)` (`/PDFoundry`)
/// is written to the file and read back after a reload, so it is the tag. `/NM` set the same way is NOT written by
/// PDFKit, so the `/NM` fallback of the plan can't be used here; `isOurs` still accepts it for files from elsewhere.
@MainActor
struct SelectionTests {
    private let folder: URL

    init() throws {
        folder = try MarkupFixture.folder("SelectionTests")
    }

    private func textFile(_ text: String = "Quarterly report for the board", rotation: Int = 0, crop: CGRect? = nil) throws -> URL {
        try MarkupFixture.write(MarkupFixture.textPDF([text], rotation: rotation, crop: crop),
                                named: "Report.pdf", in: folder)
    }

    private func viewCentre(of annotation: PDFAnnotation, in view: PDFView) -> CGPoint {
        let rect = view.convert(annotation.bounds, from: annotation.page!)
        return CGPoint(x: rect.midX, y: rect.midY)
    }

    @Test func everyMarkKindIsTaggedAndSurvivesReload() async throws {
        let url = try textFile()
        let (model, view) = await MarkupFixture.open(url)
        let page = try #require(model.document?.page(at: 0))

        for (word, type) in [("Quarterly", PDFAnnotationSubtype.highlight), ("report", .underline), ("board", .strikeOut)] {
            MarkupFixture.select(word, on: page, in: view, model: model)
            model.markup(type, color: .systemRed)
        }
        model.beginPlacing(text: "Approved")
        model.placement?.rect = CGRect(x: 60, y: 300, width: 160, height: 40)
        model.commitPlacement()
        MarkupFixture.place(MarkupFixture.signature, in: model, at: CGRect(x: 60, y: 380, width: 150, height: 60))
        model.beginPlacing(mark: .check)
        model.placement?.rect = CGRect(x: 240, y: 380, width: 40, height: 40)
        model.commitPlacement()
        model.beginDrawing()
        model.drawing = PKDrawing(strokes: [MarkupFixture.stroke(from: CGPoint(x: 80, y: 500), to: CGPoint(x: 220, y: 560))])
        model.commitDrawing()

        let made = page.annotations
        #expect(made.count == 7)
        #expect(made.allSatisfy(Annotations.isOurs))

        let reloaded = try #require(PDFDocument(url: url)?.page(at: 0))
        let types = reloaded.annotations.compactMap(\.type)
        #expect(Set(types) == ["Highlight", "Underline", "StrikeOut", "FreeText", "Stamp"])
        #expect(reloaded.annotations.count == 7)
        #expect(reloaded.annotations.allSatisfy(Annotations.isOurs), "the tag is kept in the file")
    }

    @Test func moveResizeDeleteUndoRestoresExactly() async throws {
        let url = try textFile()
        let (model, view) = await MarkupFixture.open(url)
        let page = try #require(model.document?.page(at: 0))
        MarkupFixture.place(MarkupFixture.signature, in: model, at: CGRect(x: 80, y: 300, width: 150, height: 60))
        let stamp = try #require(page.annotations.first)
        let original = stamp.bounds
        let before = try #require(PageImage.image(of: page, dpi: 72))

        model.select(at: viewCentre(of: stamp, in: view))
        #expect(model.selectedAnnotation?.annotation === stamp)
        #expect(model.selectionIsMovable)

        model.moveSelected(to: original.offsetBy(dx: 40, dy: -60))
        #expect(stamp.bounds == original.offsetBy(dx: 40, dy: -60))
        model.moveSelected(to: CGRect(origin: stamp.bounds.origin, size: CGSize(width: original.width * 1.5, height: original.height * 1.5)))
        #expect(abs(stamp.bounds.width - original.width * 1.5) < 0.01)
        // Saved after each finished change.
        let moved = try #require(PDFDocument(url: url)?.page(at: 0)?.annotations.first)
        #expect(abs(moved.bounds.width - original.width * 1.5) < 0.5)

        model.deleteSelectedAnnotation()
        #expect(page.annotations.isEmpty)
        #expect(PDFDocument(url: url)?.page(at: 0)?.annotations.isEmpty == true)

        model.undo() // delete
        model.undo() // resize
        model.undo() // move
        #expect(page.annotations.count == 1)
        let back = try #require(page.annotations.first)
        #expect(back === stamp)
        #expect(back.bounds == original)
        let after = try #require(PageImage.image(of: page, dpi: 72))
        #expect(MarkupFixture.difference(before, after) == 0, "the same appearance, not a regenerated one")

        let saved = try #require(PDFDocument(url: url)?.page(at: 0)?.annotations.first)
        #expect(abs(saved.bounds.minX - original.minX) < 0.5 && abs(saved.bounds.width - original.width) < 0.5)
        #expect(model.canUndo, "the placement itself can still be undone")
    }

    @Test func aReloadedStampKeepsItsImageWhenMovedAndResized() async throws {
        let url = try textFile("")
        do {
            let (model, _) = await MarkupFixture.open(url)
            MarkupFixture.place(MarkupFixture.signature, in: model, at: CGRect(x: 80, y: 300, width: 150, height: 60))
        }
        let (model, view) = await MarkupFixture.open(url)
        let page = try #require(model.document?.page(at: 0))
        let stamp = try #require(page.annotations.first)
        #expect(Annotations.isMovable(stamp))
        model.select(at: viewCentre(of: stamp, in: view))
        let target = CGRect(x: 300, y: 100, width: 200, height: 80)
        model.moveSelected(to: target)

        let reloaded = try #require(PDFDocument(url: url)?.page(at: 0))
        let image = try #require(PageImage.image(of: reloaded, dpi: 72))
        let centre = try #require(MarkupFixture.darkCentre(image))
        let expected = MarkupFixture.displayedFraction(of: CGPoint(x: target.midX, y: target.midY), on: reloaded)
        #expect(abs(centre.x - expected.x) < 0.02 && abs(centre.y - expected.y) < 0.02)
    }

    @Test func textMarkupIsDeletableNotMovable() async throws {
        let url = try textFile()
        let (model, view) = await MarkupFixture.open(url)
        let page = try #require(model.document?.page(at: 0))
        MarkupFixture.select("report", on: page, in: view, model: model)
        model.markup(.highlight, color: .systemYellow)
        let highlight = try #require(page.annotations.first)
        #expect(Annotations.isDeletable(highlight))
        #expect(!Annotations.isMovable(highlight))

        model.select(at: viewCentre(of: highlight, in: view))
        #expect(model.selectedAnnotation?.annotation === highlight)
        #expect(!model.selectionIsMovable)
        let bounds = highlight.bounds
        model.moveSelected(to: bounds.offsetBy(dx: 50, dy: 50))
        #expect(highlight.bounds == bounds, "text markup stays on its text")
        model.deleteSelectedAnnotation()
        #expect(page.annotations.isEmpty)
    }

    @Test func untaggedStampFromOldBuildCanBeDeleted() async throws {
        let url = try textFile()
        do {
            let document = try #require(PDFDocument(url: url))
            let page = try #require(document.page(at: 0))
            // The way builds before this one placed a signature: no tag.
            try #require(PDFOperations.stamp(MarkupFixture.signature, on: page, bounds: CGRect(x: 100, y: 200, width: 150, height: 60)))
            #expect(document.write(to: url))
        }
        let (model, view) = await MarkupFixture.open(url)
        let page = try #require(model.document?.page(at: 0))
        let stamp = try #require(page.annotations.first)
        #expect(!Annotations.isOurs(stamp))
        #expect(Annotations.isDeletable(stamp))
        #expect(!Annotations.isMovable(stamp))

        model.select(at: viewCentre(of: stamp, in: view))
        #expect(model.selectedAnnotation != nil)
        model.deleteSelectedAnnotation()
        #expect(PDFDocument(url: url)?.page(at: 0)?.annotations.isEmpty == true)
        model.undo()
        #expect(PDFDocument(url: url)?.page(at: 0)?.annotations.count == 1)
    }

    @Test func tappingElsewhereDeselects() async throws {
        let url = try textFile()
        let (model, view) = await MarkupFixture.open(url)
        let page = try #require(model.document?.page(at: 0))
        MarkupFixture.place(MarkupFixture.signature, in: model, at: CGRect(x: 80, y: 300, width: 150, height: 60))
        let stamp = try #require(page.annotations.first)
        model.handleTap(at: viewCentre(of: stamp, in: view))
        #expect(model.selectedAnnotation != nil)
        model.handleTap(at: CGPoint(x: 350, y: 700))
        #expect(model.selectedAnnotation == nil)
    }

    @Test func selectionOnRotatedCroppedPage() async throws {
        let crop = CGRect(x: 40, y: 60, width: 520, height: 680)
        // No text on the page, so the only dark pixels are the signature's.
        let url = try textFile("", rotation: 90, crop: crop)
        let (model, view) = await MarkupFixture.open(url)
        let page = try #require(model.document?.page(at: 0))
        // The landscape page is short in the view: place the signature inside it.
        let pageFrame = view.convert(page.bounds(for: .cropBox), from: page)
        MarkupFixture.place(MarkupFixture.signature, in: model,
                            at: CGRect(x: pageFrame.minX + 40, y: pageFrame.minY + 30, width: 100, height: 40))
        let stamp = try #require(page.annotations.first)
        model.select(at: viewCentre(of: stamp, in: view))
        try #require(model.selectedAnnotation != nil)

        // Moved in the view, as a drag does: the box goes right and down on screen.
        let shown = view.convert(stamp.bounds, from: page)
        let startFraction = MarkupFixture.displayedFraction(of: CGPoint(x: stamp.bounds.midX, y: stamp.bounds.midY), on: page)
        model.moveSelected(toViewRect: shown.offsetBy(dx: 120, dy: 90))
        let target = stamp.bounds
        let targetFraction = MarkupFixture.displayedFraction(of: CGPoint(x: target.midX, y: target.midY), on: page)
        #expect(targetFraction.x > startFraction.x + 0.1 && targetFraction.y > startFraction.y + 0.1, "right and down as the reader sees it")
        #expect(abs(view.convert(target, from: page).midX - shown.offsetBy(dx: 120, dy: 90).midX) < 1)

        let reloaded = try #require(PDFDocument(url: url)?.page(at: 0))
        #expect(reloaded.rotation == 90)
        let saved = try #require(reloaded.annotations.first)
        #expect(abs(saved.bounds.midX - target.midX) < 1 && abs(saved.bounds.midY - target.midY) < 1)
        let image = try #require(PageImage.image(of: reloaded, dpi: 72))
        let centre = try #require(MarkupFixture.darkCentre(image))
        let expected = MarkupFixture.displayedFraction(of: CGPoint(x: target.midX, y: target.midY), on: reloaded)
        #expect(abs(centre.x - expected.x) < 0.02, "x \(centre.x) vs \(expected.x)")
        #expect(abs(centre.y - expected.y) < 0.02, "y \(centre.y) vs \(expected.y)")
    }
}
