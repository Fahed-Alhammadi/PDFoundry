import PDFKit
import PencilKit
import SwiftUI

/// The PDF page view. Reports page changes, selection changes and taps on annotations to the model.
struct PDFKitView: UIViewRepresentable {
    let model: ReaderModel
    let horizontal: Bool

    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.displaysPageBreaks = true
        view.pageShadowsEnabled = true
        view.backgroundColor = .secondarySystemBackground
        view.isFindInteractionEnabled = true
        view.document = model.document
        model.pdfView = view

        let center = NotificationCenter.default
        center.addObserver(context.coordinator, selector: #selector(Coordinator.pageChanged), name: .PDFViewPageChanged, object: view)
        center.addObserver(context.coordinator, selector: #selector(Coordinator.selectionChanged), name: .PDFViewSelectionChanged, object: view)

        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped(_:)))
        tap.delegate = context.coordinator
        view.addGestureRecognizer(tap)
        return view
    }

    func updateUIView(_ view: PDFView, context: Context) {
        if view.document !== model.document {
            view.document = model.document
        }
        let direction: PDFDisplayDirection = horizontal ? .horizontal : .vertical
        if view.displayDirection != direction {
            view.displayDirection = direction
            view.autoScales = true
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(model: model) }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        private let model: ReaderModel

        init(model: ReaderModel) {
            self.model = model
        }

        @objc func pageChanged() {
            model.syncPage()
        }

        @objc func selectionChanged() {
            model.hasSelection = !(model.pdfView?.currentSelection?.string ?? "").isEmpty
        }

        @objc func tapped(_ recognizer: UITapGestureRecognizer) {
            guard let view = recognizer.view else { return }
            model.handleTap(at: recognizer.location(in: view))
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
            true
        }
    }
}

/// A transparent PencilKit canvas laid over the page while drawing.
struct DrawingCanvas: UIViewRepresentable {
    @Binding var drawing: PKDrawing

    func makeUIView(context: Context) -> PKCanvasView {
        let canvas = PKCanvasView()
        canvas.backgroundColor = .clear
        canvas.isOpaque = false
        canvas.drawingPolicy = .anyInput
        canvas.isScrollEnabled = false
        canvas.contentInsetAdjustmentBehavior = .never
        // Keep ink colours true on the white page in Dark Mode.
        canvas.overrideUserInterfaceStyle = .light
        canvas.tool = PKInkingTool(.pen, color: .black, width: 3)
        canvas.delegate = context.coordinator

        let picker = context.coordinator.toolPicker
        picker.addObserver(canvas)
        picker.setVisible(true, forFirstResponder: canvas)
        DispatchQueue.main.async { canvas.becomeFirstResponder() }
        return canvas
    }

    func updateUIView(_ canvas: PKCanvasView, context: Context) {
        if canvas.drawing != drawing {
            canvas.drawing = drawing
        }
    }

    static func dismantleUIView(_ canvas: PKCanvasView, coordinator: Coordinator) {
        coordinator.toolPicker.setVisible(false, forFirstResponder: canvas)
        coordinator.toolPicker.removeObserver(canvas)
        canvas.resignFirstResponder()
    }

    func makeCoordinator() -> Coordinator { Coordinator(drawing: $drawing) }

    final class Coordinator: NSObject, PKCanvasViewDelegate {
        let toolPicker = PKToolPicker()
        private let drawing: Binding<PKDrawing>

        init(drawing: Binding<PKDrawing>) {
            self.drawing = drawing
        }

        func canvasViewDrawingDidChange(_ canvasView: PKCanvasView) {
            if drawing.wrappedValue != canvasView.drawing {
                drawing.wrappedValue = canvasView.drawing
            }
        }
    }
}

/// Lets the user drag and resize a signature or text box before it is placed on the page.
struct PlacementOverlay: View {
    @Binding var placement: Placement

    @State private var dragStart: CGRect?
    @State private var resizeStart: CGRect?

    var body: some View {
        GeometryReader { proxy in
            let rect = placement.rect
            ZStack(alignment: .topLeading) {
                Color.black.opacity(0.06)
                    .allowsHitTesting(false)

                if !rect.isEmpty {
                    box
                        .frame(width: rect.width, height: rect.height)
                        .overlay {
                            RoundedRectangle(cornerRadius: 4)
                                .strokeBorder(Color.accentColor, style: StrokeStyle(lineWidth: 1.5, dash: [6, 4]))
                        }
                        .contentShape(.rect)
                        .gesture(move(in: proxy.size))
                        .overlay(alignment: .bottomTrailing) {
                            Circle()
                                .fill(Color.accentColor)
                                .frame(width: 26, height: 26)
                                .overlay {
                                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                                        .font(.system(size: 11, weight: .bold))
                                        .foregroundStyle(.white)
                                }
                                .shadow(radius: 2, y: 1)
                                .frame(width: 44, height: 44)
                                .contentShape(.rect)
                                .offset(x: 22, y: 22)
                                .gesture(resize(in: proxy.size))
                                .accessibilityLabel("Resize")
                        }
                        .offset(x: rect.minX, y: rect.minY)
                        .accessibilityLabel("Item to place. Drag to move.")
                }
            }
            .onAppear {
                guard placement.rect.isEmpty else { return }
                let width = min(proxy.size.width * 0.5, 240)
                let height = width / placement.aspect
                placement.rect = CGRect(x: (proxy.size.width - width) / 2, y: (proxy.size.height - height) / 2, width: width, height: height)
            }
        }
    }

    @ViewBuilder
    private var box: some View {
        switch placement.content {
        case .image(let image):
            Image(uiImage: image)
                .resizable()
        case .text(let text):
            Text(text)
                .font(.custom("Helvetica", fixedSize: max(6, placement.rect.height * 0.62)))
                .foregroundStyle(.black)
                .lineLimit(1)
                .minimumScaleFactor(0.3)
                .padding(.horizontal, 3)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        }
    }

    private func move(in size: CGSize) -> some Gesture {
        DragGesture()
            .onChanged { value in
                let start = dragStart ?? placement.rect
                dragStart = start
                var rect = start.offsetBy(dx: value.translation.width, dy: value.translation.height)
                rect.origin.x = min(max(rect.origin.x, 0), max(0, size.width - rect.width))
                rect.origin.y = min(max(rect.origin.y, 0), max(0, size.height - rect.height))
                placement.rect = rect
            }
            .onEnded { _ in dragStart = nil }
    }

    private func resize(in size: CGSize) -> some Gesture {
        DragGesture()
            .onChanged { value in
                let start = resizeStart ?? placement.rect
                resizeStart = start
                let width = min(max(44, start.width + value.translation.width), size.width - start.minX)
                placement.rect = CGRect(x: start.minX, y: start.minY, width: width, height: width / placement.aspect)
            }
            .onEnded { _ in resizeStart = nil }
    }
}
