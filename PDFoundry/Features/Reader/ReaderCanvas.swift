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

        // A form field's text is final once its editor closes.
        center.addObserver(context.coordinator, selector: #selector(Coordinator.fieldEditingEnded), name: UITextField.textDidEndEditingNotification, object: nil)
        center.addObserver(context.coordinator, selector: #selector(Coordinator.fieldEditingEnded), name: UITextView.textDidEndEditingNotification, object: nil)
        center.addObserver(context.coordinator, selector: #selector(Coordinator.annotationHit), name: .PDFViewAnnotationHit, object: view)

        center.addObserver(context.coordinator, selector: #selector(Coordinator.viewportChanged), name: .PDFViewScaleChanged, object: view)

        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped(_:)))
        tap.delegate = context.coordinator
        view.addGestureRecognizer(tap)

        let pan = UIPanGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.dragged(_:)))
        pan.maximumNumberOfTouches = 1
        pan.delegate = context.coordinator
        view.addGestureRecognizer(pan)
        context.coordinator.boxPan = pan

        context.coordinator.watchScrolling(of: view)
        return view
    }

    func updateUIView(_ view: PDFView, context: Context) {
        if view.document !== model.document {
            view.document = model.document
        }
        context.coordinator.setDragsBoxes(model.dragsBoxes, in: view)
        let toolOn = model.mode != .reading || model.toolMode == .redact || model.pendingRedactionCount > 0
        // After this update, when the view is in the navigation controller's hierarchy.
        DispatchQueue.main.async { [coordinator = context.coordinator] in
            coordinator.setSwipeBack(enabled: !toolOn, from: view)
        }
        let direction: PDFDisplayDirection = horizontal ? .horizontal : .vertical
        if view.displayDirection != direction {
            view.displayDirection = direction
            view.autoScales = true
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(model: model) }

    static func dismantleUIView(_ view: PDFView, coordinator: Coordinator) {
        coordinator.setSwipeBack(enabled: true, from: view)
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        private let model: ReaderModel
        private var scrollObservation: NSKeyValueObservation?

        init(model: ReaderModel) {
            self.model = model
        }

        /// Overlays drawn over the page (a selected mark, redaction boxes) follow the page as it scrolls.
        func watchScrolling(of view: PDFView) {
            DispatchQueue.main.async { [weak self, weak view] in
                guard let self, let view, let scroll = Self.scrollView(in: view) else { return }
                scrollObservation = scroll.observe(\.contentOffset, options: [.new]) { [weak self] _, _ in
                    MainActor.assumeIsolated { self?.viewportChanged() }
                }
            }
        }

        private static func scrollView(in view: UIView) -> UIScrollView? {
            for subview in view.subviews {
                if let scroll = subview as? UIScrollView { return scroll }
                if let found = scrollView(in: subview) { return found }
            }
            return nil
        }

        @objc func viewportChanged() {
            model.viewportTick &+= 1
        }

        weak var boxPan: UIPanGestureRecognizer?
        private var dragStart: CGPoint?

        /// While shapes or redaction boxes are drawn, one finger draws and the page scrolls with two.
        func setDragsBoxes(_ on: Bool, in view: PDFView) {
            boxPan?.isEnabled = on
            Self.scrollView(in: view)?.panGestureRecognizer.minimumNumberOfTouches = on ? 2 : 1
        }

        @objc func dragged(_ recognizer: UIPanGestureRecognizer) {
            guard let view = recognizer.view else { return }
            let point = recognizer.location(in: view)
            switch recognizer.state {
            case .began:
                // The pan is recognised only after the finger has moved: start where it went down.
                let start = touchDown ?? point
                dragStart = start
                model.boxDraft = (start, point)
            case .changed:
                if let dragStart { model.boxDraft = (dragStart, point) }
            case .ended:
                if let dragStart { model.finishBox(from: dragStart, to: point) }
                dragStart = nil
            default:
                dragStart = nil
                model.boxDraft = nil
            }
        }

        func gestureRecognizerShouldBegin(_ recognizer: UIGestureRecognizer) -> Bool {
            recognizer === boxPan ? model.dragsBoxes : true
        }

        /// Where the finger went down, before the pan was recognised.
        private var touchDown: CGPoint?

        func gestureRecognizer(_ recognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            if recognizer === boxPan { touchDown = touch.location(in: recognizer.view) }
            return true
        }

        /// The navigation controller's swipe-back gestures (edge and, since iOS 26, anywhere on the content) are off
        /// while a tool draws or places on the page, so a sideways drag never leaves the reader.
        func setSwipeBack(enabled: Bool, from view: UIView) {
            var responder: UIResponder? = view
            while let current = responder, !(current is UIViewController) { responder = current.next }
            guard let navigation = (responder as? UIViewController)?.navigationController else { return }
            navigation.interactivePopGestureRecognizer?.isEnabled = enabled
            navigation.interactiveContentPopGestureRecognizer?.isEnabled = enabled
        }

        @objc func pageChanged() {
            model.syncPage()
        }

        @objc func fieldEditingEnded(_ notification: Notification) {
            // Only editors inside this page view (the find bar and alerts have text fields too).
            guard let editor = notification.object as? UIView, let view = model.pdfView, editor.isDescendant(of: view) else { return }
            model.syncFields()
        }

        @objc func annotationHit() {
            model.scheduleFieldSync()
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
    /// Where the last item was placed, so the next one starts beside it instead of on top of it.
    var avoiding: CGRect?

    /// Where a new item starts: centred, or, if that covers the last item placed, just below it (or above it when
    /// there's no room below).
    static func startRect(aspect: CGFloat, in size: CGSize, avoiding last: CGRect?) -> CGRect {
        let width = min(size.width * 0.5, 240)
        let height = width / max(aspect, 0.01)
        var rect = CGRect(x: (size.width - width) / 2, y: (size.height - height) / 2, width: width, height: height)
        guard let last, rect.intersects(last) else { return rect }
        if last.maxY + 12 + height <= size.height {
            rect.origin.y = last.maxY + 12
        } else if last.minY - 12 - height >= 0 {
            rect.origin.y = last.minY - 12 - height
        }
        return rect
    }

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
                placement.rect = Self.startRect(aspect: placement.aspect, in: proxy.size, avoiding: avoiding)
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
