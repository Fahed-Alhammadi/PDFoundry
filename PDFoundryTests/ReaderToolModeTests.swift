import PDFKit
import PencilKit
import Testing
import UIKit
@testable import PDFoundry

/// The reader's bottom bar shows a tool mode (Markup, Fill & Sign) that stays open while the user works in it.
@MainActor
struct ReaderToolModeTests {
    private let folder: URL

    init() throws {
        folder = FileManager.default.temporaryDirectory.appending(path: "ReaderToolModeTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    private func makePDF(pages: Int = 1) throws -> URL {
        let data = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792)).pdfData { context in
            for _ in 0..<pages { context.beginPage() }
        }
        let url = folder.appending(path: "Contract.pdf")
        try data.write(to: url)
        return url
    }

    /// A model showing the document in a real PDFView, as the reader does.
    private func openModel() async throws -> (ReaderModel, PDFView) {
        let model = ReaderModel(url: try makePDF())
        await model.load()
        let view = PDFView(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        view.autoScales = true
        view.document = model.document
        view.layoutIfNeeded()
        model.pdfView = view
        return (model, view)
    }

    private var signature: UIImage {
        UIGraphicsImageRenderer(size: CGSize(width: 200, height: 80)).image { context in
            UIColor.black.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 200, height: 80))
        }
    }

    @Test func toolModeStartsAtNone() async throws {
        let model = ReaderModel(url: try makePDF())
        #expect(model.toolMode == .none)
        await model.load()
        #expect(model.toolMode == .none)
    }

    @Test func placingASignatureKeepsFillSign() async throws {
        let (model, view) = try await openModel()
        model.toolMode = .fillSign

        for round in 1...2 {
            model.beginPlacing(signature: signature)
            #expect(model.mode == .placing)
            model.placement?.rect = CGRect(x: 100, y: 200 + CGFloat(round) * 100, width: 150, height: 60)
            model.commitPlacement()

            #expect(model.mode == .reading)
            #expect(model.toolMode == .fillSign, "the bar stays in Fill & Sign so the next signature is one tap")
        }
        let stamps = view.document?.page(at: 0)?.annotations.filter { $0.type == "Stamp" } ?? []
        #expect(stamps.count == 2)
    }

    @Test func cancellingADrawingKeepsMarkup() async throws {
        let (model, _) = try await openModel()
        model.toolMode = .markup

        model.beginDrawing()
        #expect(model.mode == .drawing)
        model.cancelMode()
        #expect(model.mode == .reading)
        #expect(model.toolMode == .markup)

        model.beginDrawing()
        model.commitDrawing() // nothing drawn: still returns to the Markup bar
        #expect(model.mode == .reading)
        #expect(model.toolMode == .markup)
    }

    @Test func placedTextKeepsTheModeItCameFrom() async throws {
        let (model, _) = try await openModel()
        for toolMode in [ReaderModel.ToolMode.markup, .fillSign] {
            model.toolMode = toolMode
            model.beginPlacing(text: "Approved")
            model.placement?.rect = CGRect(x: 80, y: 300, width: 160, height: 40)
            model.commitPlacement()
            #expect(model.toolMode == toolMode)
        }
        #expect(model.canUndo)
    }
}
