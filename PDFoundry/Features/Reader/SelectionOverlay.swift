import PDFKit
import SwiftUI

/// The selected mark: a box with corner handles and a small menu. Drag inside the box to move the mark, drag a
/// corner to resize it (stamps and text keep their shape; shapes stretch freely). Text markup and marks made by
/// other apps show the box and the menu only.
struct SelectionOverlay: View {
    let model: ReaderModel
    let onOpenComment: (PDFAnnotation) -> Void

    @State private var startFrame: CGRect?

    private enum Corner: CaseIterable { case topLeft, topRight, bottomLeft, bottomRight }

    var body: some View {
        GeometryReader { proxy in
            if let selected = model.selectedAnnotation, let frame = model.selectionFrame {
                let box = frame.insetBy(dx: -3, dy: -3)
                ZStack(alignment: .topLeading) {
                    Color.clear.allowsHitTesting(false)

                    Rectangle()
                        .fill(Color.accentColor.opacity(0.06))
                        .overlay { Rectangle().strokeBorder(Color.accentColor, lineWidth: 1.5) }
                        .frame(width: max(box.width, 1), height: max(box.height, 1))
                        .contentShape(.rect)
                        .gesture(move(frame), including: selected.isMovable ? .all : .none)
                        .offset(x: box.minX, y: box.minY)
                        .accessibilityLabel("Selected \(selected.title)")
                        .accessibilityHint(selected.isMovable ? "Drag to move." : "")

                    if selected.isMovable {
                        ForEach(Corner.allCases, id: \.self) { corner in
                            handle
                                .position(point(of: corner, in: box))
                                .gesture(resize(corner, frame: frame, keepsShape: Annotations.keepsProportion(selected.annotation)))
                        }
                    }

                    menu(for: selected)
                        .fixedSize()
                        .position(x: min(max(box.midX, 90), max(90, proxy.size.width - 90)),
                                  y: box.minY > 120 ? box.minY - 34 : min(box.maxY + 34, proxy.size.height - 30))
                }
            }
        }
    }

    private var handle: some View {
        Circle()
            .fill(.white)
            .overlay { Circle().strokeBorder(Color.accentColor, lineWidth: 2) }
            .frame(width: 16, height: 16)
            .shadow(radius: 1.5, y: 1)
            .frame(width: 44, height: 44)
            .contentShape(.rect)
            .accessibilityLabel("Resize")
    }

    private func menu(for selected: AnnotationSelection) -> some View {
        HStack(spacing: 0) {
            if selected.isComment {
                Button("Open") { onOpenComment(selected.annotation) }
                    .padding(.horizontal, 14)
                Divider().frame(height: 22)
            }
            Button("Delete", role: .destructive) { model.deleteSelectedAnnotation() }
                .padding(.horizontal, 14)
        }
        .font(.subheadline.weight(.medium))
        .padding(.vertical, 9)
        .glassEffect(.regular.interactive(), in: .capsule)
    }

    private func point(of corner: Corner, in rect: CGRect) -> CGPoint {
        switch corner {
        case .topLeft: CGPoint(x: rect.minX, y: rect.minY)
        case .topRight: CGPoint(x: rect.maxX, y: rect.minY)
        case .bottomLeft: CGPoint(x: rect.minX, y: rect.maxY)
        case .bottomRight: CGPoint(x: rect.maxX, y: rect.maxY)
        }
    }

    private func move(_ frame: CGRect) -> some Gesture {
        DragGesture(minimumDistance: 2)
            .onChanged { value in
                let start = startFrame ?? frame
                startFrame = start
                model.dragSelected(toViewRect: start.offsetBy(dx: value.translation.width, dy: value.translation.height))
            }
            .onEnded { _ in
                startFrame = nil
                model.finishDrag()
            }
    }

    private func resize(_ corner: Corner, frame: CGRect, keepsShape: Bool) -> some Gesture {
        DragGesture(minimumDistance: 1)
            .onChanged { value in
                let start = startFrame ?? frame
                startFrame = start
                model.dragSelected(toViewRect: Self.resized(start, corner: corner, by: value.translation, keepsShape: keepsShape))
            }
            .onEnded { _ in
                startFrame = nil
                model.finishDrag()
            }
    }

    /// `rect` with `corner` dragged by `delta`; the opposite corner stays put.
    private static func resized(_ rect: CGRect, corner: Corner, by delta: CGSize, keepsShape: Bool) -> CGRect {
        let minimum: CGFloat = 14
        var left = rect.minX, right = rect.maxX, top = rect.minY, bottom = rect.maxY
        switch corner {
        case .topLeft: left += delta.width; top += delta.height
        case .topRight: right += delta.width; top += delta.height
        case .bottomLeft: left += delta.width; bottom += delta.height
        case .bottomRight: right += delta.width; bottom += delta.height
        }
        var width = max(minimum, right - left), height = max(minimum, bottom - top)
        if keepsShape, rect.width > 0, rect.height > 0 {
            let scale = max(width / rect.width, height / rect.height, minimum / min(rect.width, rect.height))
            width = rect.width * scale
            height = rect.height * scale
        }
        let x = corner == .topLeft || corner == .bottomLeft ? rect.maxX - width : rect.minX
        let y = corner == .topLeft || corner == .topRight ? rect.maxY - height : rect.minY
        return CGRect(x: x, y: y, width: width, height: height)
    }
}
