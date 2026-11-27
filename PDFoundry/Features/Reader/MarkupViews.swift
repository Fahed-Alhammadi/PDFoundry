import PDFKit
import SwiftUI

/// Drawn over the page: the shape or box being dragged, and the redaction boxes not applied yet (red outlines, each
/// with a button to remove it).
struct BoxOverlay: View {
    let model: ReaderModel

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.clear.allowsHitTesting(false)

            if model.toolMode == .redact {
                ForEach(model.pendingRedactionFrames, id: \.frame.debugDescription) { box in
                    Rectangle()
                        .fill(Color.red.opacity(0.12))
                        .overlay { Rectangle().strokeBorder(Color.red, lineWidth: 2) }
                        .frame(width: box.frame.width, height: box.frame.height)
                        .offset(x: box.frame.minX, y: box.frame.minY)
                        .allowsHitTesting(false)
                    Button {
                        model.removeRedaction(page: box.page, at: box.position)
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 20))
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(.white, .red)
                            .frame(width: 44, height: 44)
                            .contentShape(.rect)
                    }
                    .accessibilityLabel("Remove this redaction box")
                    .position(x: box.frame.maxX, y: box.frame.minY)
                }
            }

            if let draft = model.boxDraft {
                draftShape(from: draft.from, to: draft.to)
                    .allowsHitTesting(false)
            }
        }
    }

    @ViewBuilder
    private func draftShape(from: CGPoint, to: CGPoint) -> some View {
        let rect = CGRect(x: min(from.x, to.x), y: min(from.y, to.y), width: abs(to.x - from.x), height: abs(to.y - from.y))
        if model.mode == .shaping {
            let color = Color(model.shapeColor)
            switch model.shapeKind {
            case .rectangle:
                Rectangle().stroke(color, lineWidth: 2)
                    .frame(width: rect.width, height: rect.height).offset(x: rect.minX, y: rect.minY)
            case .ellipse:
                Ellipse().stroke(color, lineWidth: 2)
                    .frame(width: rect.width, height: rect.height).offset(x: rect.minX, y: rect.minY)
            case .line, .arrow:
                Path { path in
                    path.move(to: from)
                    path.addLine(to: to)
                }
                .stroke(color, style: StrokeStyle(lineWidth: 2, lineCap: .round))
            }
        } else {
            Rectangle()
                .fill(Color.red.opacity(0.12))
                .overlay { Rectangle().strokeBorder(Color.red, style: StrokeStyle(lineWidth: 2, dash: [5, 3])) }
                .frame(width: rect.width, height: rect.height)
                .offset(x: rect.minX, y: rect.minY)
        }
    }
}

/// Writes a new comment, or reads, edits and deletes one. Done saves; an empty comment is not kept.
struct CommentSheet: View {
    @State var text: String
    let isNew: Bool
    let onDone: (String) -> Void
    let onDelete: () -> Void

    @Environment(\.dismiss) private var dismiss
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            TextEditor(text: $text)
                .focused($focused)
                .padding(.horizontal, 12)
                .scrollContentBackground(.hidden)
                .background(Color(.secondarySystemBackground), in: .rect(cornerRadius: 12))
                .padding()
                .accessibilityLabel("Comment")
                .navigationTitle(isNew ? "New Comment" : "Comment")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") {
                            onDone(text)
                            dismiss()
                        }
                        .disabled(isNew && text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                    if !isNew {
                        ToolbarItem(placement: .bottomBar) {
                            Button("Delete Comment", role: .destructive) {
                                onDelete()
                                dismiss()
                            }
                        }
                    }
                }
        }
        .onAppear { focused = isNew }
    }
}
