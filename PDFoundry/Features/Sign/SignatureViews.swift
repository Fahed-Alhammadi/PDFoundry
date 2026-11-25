import PencilKit
import SwiftUI

/// Pick a saved signature or draw a new one. `onPick` receives the signature to place.
struct SignatureSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(SignatureStore.self) private var signatures
    let onPick: (UIImage) -> Void

    @State private var showPad = false

    var body: some View {
        NavigationStack {
            Group {
                if signatures.signatures.isEmpty {
                    SignaturePad(onDone: use)
                } else {
                    SignatureList { signature in
                        use(signature.image)
                    }
                    .navigationDestination(isPresented: $showPad) {
                        SignaturePad(onDone: use)
                    }
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button("New Signature", systemImage: "plus") { showPad = true }
                        }
                    }
                }
            }
            .navigationTitle("Signature")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func use(_ image: UIImage) {
        dismiss()
        onPick(image)
    }
}

/// Saved signatures. Used when signing and in Settings.
struct SignatureList: View {
    @Environment(SignatureStore.self) private var signatures
    var onPick: ((SavedSignature) -> Void)?

    var body: some View {
        List {
            Section {
                ForEach(signatures.signatures) { signature in
                    Button {
                        onPick?(signature)
                    } label: {
                        Image(uiImage: signature.image)
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: .infinity)
                            .frame(height: 76)
                            .padding(10)
                            .background(.white, in: .rect(cornerRadius: 10))
                    }
                    .buttonStyle(.plain)
                    .disabled(onPick == nil)
                    .listRowInsets(EdgeInsets(top: 8, leading: 12, bottom: 8, trailing: 12))
                    .swipeActions {
                        Button("Delete", systemImage: "trash", role: .destructive) { signatures.delete(signature) }
                    }
                    .accessibilityLabel("Saved signature")
                }
            } footer: {
                Text(onPick == nil ? "Swipe a signature to delete it. Signatures are stored only on this device." : "Tap a signature to place it. Swipe to delete.")
            }
        }
    }
}

/// A pad for drawing a signature with a finger or Apple Pencil.
struct SignaturePad: View {
    private enum Ink: String, CaseIterable, Identifiable {
        case black, blue

        var id: String { rawValue }
        var title: String { rawValue.capitalized }
        var color: UIColor {
            switch self {
            case .black: .black
            case .blue: UIColor(red: 0.05, green: 0.2, blue: 0.62, alpha: 1)
            }
        }
    }

    @Environment(SignatureStore.self) private var signatures
    let onDone: (UIImage) -> Void

    @State private var drawing = PKDrawing()
    @State private var ink = Ink.black
    @State private var keep = true

    var body: some View {
        VStack(spacing: 16) {
            ZStack(alignment: .bottom) {
                RoundedRectangle(cornerRadius: 16)
                    .fill(.white)
                VStack(spacing: 6) {
                    Rectangle()
                        .fill(.gray.opacity(0.45))
                        .frame(height: 1)
                    Text("Sign above the line")
                        .font(.caption)
                        .foregroundStyle(.gray)
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 16)
                .allowsHitTesting(false)

                SignatureCanvas(drawing: $drawing, color: ink.color)
                    .clipShape(.rect(cornerRadius: 16))
            }
            .frame(minHeight: 170, maxHeight: 260)
            .overlay { RoundedRectangle(cornerRadius: 16).strokeBorder(.quaternary) }

            HStack {
                Picker("Ink Color", selection: $ink) {
                    ForEach(Ink.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented)
                .frame(maxWidth: 180)
                Spacer()
                Button("Clear") { drawing = PKDrawing() }
                    .disabled(drawing.strokes.isEmpty)
            }

            Toggle("Save for Next Time", isOn: $keep)

            Button {
                guard let image = rendered() else { return }
                if keep { signatures.add(image) }
                onDone(image)
            } label: {
                Text("Use Signature")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
            }
            .buttonStyle(.glassProminent)
            .disabled(drawing.strokes.isEmpty)

            Spacer(minLength: 0)
        }
        .padding(20)
        .navigationTitle("New Signature")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func rendered() -> UIImage? {
        guard !drawing.strokes.isEmpty else { return nil }
        var image: UIImage?
        let captured = drawing
        UITraitCollection(userInterfaceStyle: .light).performAsCurrent {
            image = captured.image(from: captured.bounds.insetBy(dx: -6, dy: -6), scale: 3)
        }
        return image
    }
}

private struct SignatureCanvas: UIViewRepresentable {
    @Binding var drawing: PKDrawing
    let color: UIColor

    func makeUIView(context: Context) -> PKCanvasView {
        let canvas = PKCanvasView()
        canvas.backgroundColor = .clear
        canvas.isOpaque = false
        canvas.drawingPolicy = .anyInput
        canvas.isScrollEnabled = false
        canvas.contentInsetAdjustmentBehavior = .never
        canvas.overrideUserInterfaceStyle = .light
        canvas.delegate = context.coordinator
        return canvas
    }

    func updateUIView(_ canvas: PKCanvasView, context: Context) {
        canvas.tool = PKInkingTool(.pen, color: color, width: 4)
        if canvas.drawing != drawing {
            canvas.drawing = drawing
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(drawing: $drawing) }

    final class Coordinator: NSObject, PKCanvasViewDelegate {
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
