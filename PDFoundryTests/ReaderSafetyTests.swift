import PDFKit
import Testing
import UIKit
@testable import PDFoundry

/// Waiting redaction boxes are never left over content they weren't drawn on, and a drag that never finished
/// can't leak into the next one.
@MainActor
struct ReaderSafetyTests {
    private let folder: URL

    init() throws {
        folder = try MarkupFixture.folder("ReaderSafetyTests")
    }

    private func open(password: String? = nil) async throws -> (ReaderModel, PDFView, URL) {
        let url = folder.appending(path: "Doc.pdf")
        let data = MarkupFixture.textPDF(["First page", "Second page"])
        if let password {
            let document = try #require(PDFDocument(data: data))
            #expect(document.write(to: url, withOptions: PDFOperations.writeOptions(password: password)))
        } else {
            try data.write(to: url)
        }
        let (model, view) = await MarkupFixture.open(url, password: password)
        return (model, view, url)
    }

    @Test func revertAsksWhileBoxesWait() async throws {
        let (model, _, url) = try await open()
        MarkupFixture.place(MarkupFixture.signature, in: model, at: CGRect(x: 80, y: 300, width: 150, height: 60))
        #expect(model.canRevert)
        model.addRedaction(CGRect(x: 10, y: 10, width: 100, height: 40), page: 0)
        let changed = try Data(contentsOf: url)

        #expect(model.revertToOriginal() == false)
        #expect(model.leaveRequest == .revert)
        #expect(try Data(contentsOf: url) == changed, "nothing reverted yet")
        #expect(model.pendingRedactionCount == 1)

        model.leaveRequest = nil
        model.discardRedactions()
        #expect(model.revertToOriginal())
    }

    @Test func undoRemovePasswordAndPagesAskWhileBoxesWait() async throws {
        let (model, _, _) = try await open(password: "pw")
        MarkupFixture.place(MarkupFixture.signature, in: model, at: CGRect(x: 80, y: 300, width: 150, height: 60))
        model.addRedaction(CGRect(x: 10, y: 10, width: 100, height: 40), page: 1)

        model.undo()
        #expect(model.leaveRequest == .undo)
        #expect(model.document?.page(at: 0)?.annotations.count == 1, "the signature is still there")

        model.leaveRequest = nil
        #expect(model.removePassword() == false)
        #expect(model.leaveRequest == .removePassword)
        #expect(model.isProtected)

        model.leaveRequest = nil
        #expect(model.askAboutRedactions(before: .organizePages))
        #expect(model.leaveRequest == .organizePages)

        model.leaveRequest = nil
        model.discardRedactions()
        #expect(model.askAboutRedactions(before: .organizePages) == false)
        model.undo()
        #expect(model.document?.page(at: 0)?.annotations.isEmpty == true)
    }

    @Test func anUnfinishedDragDoesNotLeakIntoTheNextMark() async throws {
        let (model, view, _) = try await open()
        let page = try #require(model.document?.page(at: 0))
        MarkupFixture.place(MarkupFixture.signature, in: model, at: CGRect(x: 60, y: 200, width: 120, height: 50))
        MarkupFixture.place(MarkupFixture.signature, in: model, at: CGRect(x: 60, y: 400, width: 120, height: 50))
        let first = page.annotations[0], second = page.annotations[1]
        let firstStart = first.bounds, secondStart = second.bounds

        func centre(_ annotation: PDFAnnotation) -> CGPoint {
            let rect = view.convert(annotation.bounds, from: page)
            return CGPoint(x: rect.midX, y: rect.midY)
        }
        // A drag on the first mark whose end never arrives.
        model.select(at: centre(first))
        model.dragSelected(toViewRect: view.convert(first.bounds, from: page).offsetBy(dx: 40, dy: 0))
        let firstMoved = first.bounds
        #expect(firstMoved != firstStart)

        // The user taps the second mark and moves it.
        model.select(at: centre(second))
        #expect(model.selectedAnnotation?.annotation === second)
        model.dragSelected(toViewRect: view.convert(second.bounds, from: page).offsetBy(dx: 0, dy: 30))
        model.finishDrag()
        #expect(second.bounds != secondStart)
        #expect(first.bounds == firstMoved, "the first mark is not moved again")

        model.undo()
        #expect(second.bounds == secondStart, "Undo puts the second mark back where it was, not where the first one started")
        #expect(first.bounds == firstMoved)
        model.undo()
        #expect(first.bounds == firstStart, "the unfinished drag became its own step")

        // A finish with nothing selected clears the leftover start.
        model.select(at: centre(first))
        model.dragSelected(toViewRect: view.convert(first.bounds, from: page).offsetBy(dx: 10, dy: 0))
        model.selectedAnnotation = nil
        model.finishDrag()
        model.select(at: centre(second))
        model.moveSelected(toViewRect: view.convert(second.bounds, from: page).offsetBy(dx: 5, dy: 0))
        model.undo()
        #expect(second.bounds == secondStart)
    }
}
