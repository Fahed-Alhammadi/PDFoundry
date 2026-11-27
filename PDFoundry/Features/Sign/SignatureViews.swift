import PencilKit
import SwiftUI

extension EnvironmentValues {
    /// The saved initials. Signatures are the environment's `SignatureStore`.
    @Entry var initialsStore: SignatureStore?
}

extension SignatureKind {
    var title: String { self == .signature ? "Signature" : "Initials" }
    var newTitle: String { self == .signature ? "New Signature" : "New Initials" }
}

/// Pick a saved signature (or initials) or draw a new one. `onPick` receives the image to place.
struct SignatureSheet: View {
    var kind = SignatureKind.signature
    let onPick: (UIImage) -> Void

    @Environment(SignatureStore.self) private var savedSignatures
    @Environment(\.initialsStore) private var savedInitials

    var body: some View {
        // The lists and the pad below read the store for this kind from the environment.
        SignatureSheetContent(kind: kind, onPick: onPick)
            .environment(kind == .initials ? (savedInitials ?? savedSignatures) : savedSignatures)
    }
}

private struct SignatureSheetContent: View {
    let kind: SignatureKind
    let onPick: (UIImage) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(SignatureStore.self) private var signatures
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
                            Button(kind.newTitle, systemImage: "plus") { showPad = true }
                        }
                    }
                }
            }
            .navigationTitle(kind.title)
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
                    .accessibilityLabel(signatures.kind == .initials ? "Saved initials" : "Saved signature")
                }
            } footer: {
                Text(footer)
            }
        }
    }
}

extension SignatureList {
    private var footer: String {
        switch (signatures.kind, onPick == nil) {
        case (.signature, true): "Swipe a signature to delete it. Signatures are stored only on this device."
        case (.signature, false): "Tap a signature to place it. Swipe to delete."
        case (.initials, true): "Swipe initials to delete them. Initials are stored only on this device."
        case (.initials, false): "Tap your initials to place them. Swipe to delete."
        }
    }
}

/// A pad for drawing a signature (or initials) with a finger or Apple Pencil.
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
                    Text(signatures.kind == .initials ? "Write your initials above the line" : "Sign above the line")
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
                Text(signatures.kind == .initials ? "Use Initials" : "Use Signature")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
            }
            .buttonStyle(.glassProminent)
            .disabled(drawing.strokes.isEmpty)

            Spacer(minLength: 0)
        }
        .padding(20)
        .navigationTitle(signatures.kind.newTitle)
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
