import PDFKit
import SwiftUI

/// A small picture of a page, with an overlay drawn in the page's own points (as shown), for live previews.
struct PagePreview<Overlay: View>: View {
    let page: PDFPage?
    @ViewBuilder let overlay: (_ pageSize: CGSize) -> Overlay

    @State private var image: UIImage?

    var body: some View {
        let size = page.map { PageImage.displayedSize(of: $0) } ?? CGSize(width: 612, height: 792)
        GeometryReader { proxy in
            let scale = min(proxy.size.width / size.width, proxy.size.height / size.height)
            ZStack(alignment: .topLeading) {
                Group {
                    if let image {
                        Image(uiImage: image).resizable()
                    } else {
                        Color.white
                    }
                }
                overlay(size)
                    .frame(width: size.width, height: size.height, alignment: .topLeading)
                    .scaleEffect(scale, anchor: .topLeading)
                    .frame(width: size.width * scale, height: size.height * scale, alignment: .topLeading)
            }
            .frame(width: size.width * scale, height: size.height * scale)
            .clipShape(.rect(cornerRadius: 4))
            .shadow(color: .black.opacity(0.18), radius: 4, y: 1)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .aspectRatio(size.width / max(size.height, 1), contentMode: .fit)
        .frame(maxHeight: 260)
        .task(id: page.map(ObjectIdentifier.init)) {
            guard let page, let rendered = PageImage.image(of: page, dpi: 48) else { return }
            image = UIImage(cgImage: rendered)
        }
        .accessibilityHidden(true)
    }
}

/// Page Numbers, from the reader (its More menu, or the Tools tab through the reader): where, how, and from which
/// number. Add numbers the pages as one change that Undo and Revert take back.
struct PageNumbersSheet: View {
    let model: ReaderModel

    @Environment(\.dismiss) private var dismiss
    @State private var style = PageNumberStyle()

    private var previewIndex: Int { style.skipFirst && model.pageCount > 1 ? 1 : 0 }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    PagePreview(page: model.document?.page(at: previewIndex)) { size in
                        if let label = style.label(forPage: previewIndex, count: model.pageCount) {
                            Text(label)
                                .font(.custom("Helvetica", fixedSize: PageStamps.numberSize))
                                .foregroundStyle(Color(Mark.ink))
                                .fixedSize()
                                .frame(width: size.width - 2 * PageStamps.margin, height: size.height - 2 * PageStamps.margin,
                                       alignment: alignment)
                                .padding(PageStamps.margin)
                        }
                    }
                    .listRowBackground(Color.clear)
                }

                Section {
                    Picker("Position", selection: $style.position) {
                        ForEach(PageNumberStyle.Position.allCases, id: \.self) { Text($0.title).tag($0) }
                    }
                    Picker("Format", selection: $style.format) {
                        ForEach(PageNumberStyle.Format.allCases, id: \.self) { Text($0.example).tag($0) }
                    }
                    Stepper(value: $style.startAt, in: 1...9999) {
                        LabeledContent("Start at", value: "\(style.startAt)")
                    }
                    Toggle("Skip the first page", isOn: $style.skipFirst)
                } footer: {
                    Text(style.skipFirst ? "The first page has no number; numbering starts on page 2." : "Every page gets a number.")
                }
            }
            .navigationTitle("Page Numbers")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        model.addPageNumbers(style)
                        dismiss()
                    }
                }
            }
        }
    }

    private var alignment: Alignment {
        switch style.position {
        case .bottomCenter: .bottom
        case .bottomRight: .bottomTrailing
        case .bottomLeft: .bottomLeading
        case .topCenter: .top
        case .topRight: .topTrailing
        case .topLeft: .topLeading
        }
    }
}

/// Watermark, from the reader: the text, how strong, which way, and which colour, on every page.
struct WatermarkSheet: View {
    let model: ReaderModel

    @Environment(\.dismiss) private var dismiss
    @State private var style = WatermarkStyle()
    @State private var colorName = "Graphite"

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    PagePreview(page: model.document?.page(at: 0)) { size in
                        watermark(in: size)
                    }
                    .listRowBackground(Color.clear)
                }

                Section("Text") {
                    TextField("Watermark text", text: $style.text)
                        .textInputAutocapitalization(.characters)
                    Picker("Quick choices", selection: $style.text) {
                        ForEach(WatermarkStyle.quickTexts, id: \.self) { Text($0).tag($0) }
                        if !WatermarkStyle.quickTexts.contains(style.text) { Text("Custom").tag(style.text) }
                    }
                    .pickerStyle(.menu)
                }

                Section {
                    LabeledContent("Opacity") {
                        Slider(value: $style.opacity, in: 0.05...1, step: 0.05)
                            .frame(maxWidth: 200)
                        Text("\(Int((style.opacity * 100).rounded()))%")
                            .monospacedDigit()
                            .frame(width: 44, alignment: .trailing)
                    }
                    Picker("Direction", selection: $style.diagonal) {
                        Text("Diagonal").tag(true)
                        Text("Horizontal").tag(false)
                    }
                    .pickerStyle(.segmented)
                    Picker("Colour", selection: $colorName) {
                        ForEach(WatermarkStyle.colors, id: \.name) { Text($0.name).tag($0.name) }
                    }
                    .pickerStyle(.segmented)
                } footer: {
                    Text("Stamped across every page, on top of everything.")
                }
            }
            .onChange(of: colorName) { _, name in
                style.color = WatermarkStyle.colors.first { $0.name == name }?.color ?? Mark.ink
            }
            .navigationTitle("Watermark")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        model.addWatermark(style)
                        dismiss()
                    }
                    .disabled(style.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }

    private func watermark(in size: CGSize) -> some View {
        let angle = style.diagonal ? atan2(size.height, size.width) : 0
        return Text(style.text)
            .font(.custom("Helvetica", fixedSize: 100))
            .foregroundStyle(Color(style.color))
            .lineLimit(1)
            .fixedSize()
            .minimumScaleFactor(0.01)
            .frame(width: size.width * 0.6)
            .scaleEffect(1)
            .opacity(style.opacity)
            .rotationEffect(.radians(-angle))
            .frame(width: size.width, height: size.height)
    }
}
