import PDFKit
import SwiftUI

/// The reader's bottom bar. While reading it offers the modes (Markup, Fill & Sign, Pages) and Undo; in a mode it
/// shows that mode's tools, with a close button on the left. Undo stays at the right end in every state.
///
/// Every button carries a title under its icon. When the titles don't fit (a small phone, large text) they get
/// smaller, and at accessibility text sizes the bar shows icons only; the title is then still read out by
/// VoiceOver and shown in the large content viewer when the button is pressed and held.
struct ModeBar: View {
    let model: ReaderModel
    let onPages: () -> Void
    let onText: () -> Void
    let onSignature: () -> Void
    let onInitials: () -> Void
    let onDate: () -> Void

    @Environment(\.dynamicTypeSize) private var typeSize
    @Namespace private var glass

    /// Highlight colours, the same on every platform.
    static let highlights: [(name: String, color: UIColor)] = [
        ("Yellow", .systemYellow.withAlphaComponent(0.5)),
        ("Green", .systemGreen.withAlphaComponent(0.4)),
        ("Pink", .systemPink.withAlphaComponent(0.4)),
        ("Blue", .systemBlue.withAlphaComponent(0.35)),
    ]

    var body: some View {
        GlassEffectContainer(spacing: 8) {
            HStack(spacing: 8) {
                if model.toolMode != .none {
                    RoundBarButton(title: closeTitle, symbol: "xmark") { model.toolMode = .none }
                        .glassEffectID("close", in: glass)
                }

                toolRow
                    .padding(.horizontal, 6)
                    .frame(minHeight: 56)
                    .glassEffect(.regular.interactive(), in: .capsule)
                    .glassEffectID("tools", in: glass)

                RoundBarButton(title: "Undo", symbol: "arrow.uturn.backward") { model.undo() }
                    .disabled(!model.canUndo)
                    .glassEffectID("undo", in: glass)
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 6)
        .animation(.snappy, value: model.toolMode)
    }

    private var closeTitle: String {
        model.toolMode == .markup ? "Close Markup" : "Close Fill & Sign"
    }

    /// The first layout that fits: titles at their size, smaller titles, Check and Cross folded into one Mark
    /// menu, then icons only.
    @ViewBuilder
    private var toolRow: some View {
        if typeSize.isAccessibilitySize {
            row(titleSize: nil, foldMarks: true)
        } else {
            ViewThatFits(in: .horizontal) {
                row(titleSize: 11, foldMarks: false)
                row(titleSize: 10, foldMarks: false)
                row(titleSize: 9, foldMarks: false)
                row(titleSize: 10, foldMarks: true)
                row(titleSize: 9, foldMarks: true)
                row(titleSize: nil, foldMarks: true)
            }
        }
    }

    /// One row of tools. `titleSize` nil shows icons only.
    private func row(titleSize: CGFloat?, foldMarks: Bool) -> some View {
        // Evenly spread: equal gaps between the tools and at both ends.
        HStack(spacing: 0) {
            Spacer(minLength: 0)
            ForEach(Array(items(foldMarks: foldMarks).enumerated()), id: \.element.title) { index, item in
                if index > 0 { Spacer(minLength: 4) }
                item.view(titleSize: titleSize)
            }
            Spacer(minLength: 0)
        }
        // Titles don't grow past the largest standard size; past that the row shows icons only.
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
    }

    private func items(foldMarks: Bool) -> [BarItem] {
        switch model.toolMode {
        case .none:
            [
                BarItem("Markup", symbol: "pencil.tip.crop.circle") { model.toolMode = .markup },
                BarItem("Fill & Sign", symbol: "signature") { model.toolMode = .fillSign },
                BarItem("Pages", symbol: "square.grid.2x2", action: onPages),
            ]
        case .markup:
            [
                BarItem("Highlight", symbol: "highlighter", hint: "Shows the colours", menu: Self.highlights.map { highlight in
                    BarChoice(title: highlight.name) { model.markup(.highlight, color: highlight.color) }
                }),
                BarItem("Underline", symbol: "underline") { model.markup(.underline, color: .systemRed) },
                BarItem("Strikethrough", symbol: "strikethrough") { model.markup(.strikeOut, color: .systemRed) },
                BarItem("Text", symbol: "textformat", action: onText),
                BarItem("Draw", symbol: "scribble.variable") { model.beginDrawing() },
            ]
        case .fillSign:
            [
                BarItem("Signature", symbol: "signature", action: onSignature),
                BarItem("Initials", symbol: "characters.uppercase", action: onInitials),
                BarItem("Text", symbol: "textformat", action: onText),
                BarItem("Date", symbol: "calendar", action: onDate),
            ] + markItems(folded: foldMarks)
        }
    }

    /// Check and Cross: two buttons, or one Mark menu when the bar is short of room.
    private func markItems(folded: Bool) -> [BarItem] {
        let marks = Mark.allCases.map { mark in
            BarItem(mark.title, symbol: mark.symbol) { model.beginPlacing(mark: mark) }
        }
        guard folded else { return marks }
        return [BarItem("Mark", symbol: "checkmark.square", hint: "Shows Check and Cross", menu: Mark.allCases.map { mark in
            BarChoice(title: mark.title, symbol: mark.symbol) { model.beginPlacing(mark: mark) }
        })]
    }
}

/// One choice in a bar item's menu.
private struct BarChoice {
    let title: String
    var symbol: String?
    let action: () -> Void
}

/// One tool in the bar: a button, or a menu of choices (Highlight's colours, Check and Cross).
private struct BarItem {
    let title: String
    let symbol: String
    var action: () -> Void = {}
    var menu: [BarChoice]?
    var hint = ""

    init(_ title: String, symbol: String, action: @escaping () -> Void) {
        self.title = title
        self.symbol = symbol
        self.action = action
    }

    init(_ title: String, symbol: String, hint: String, menu: [BarChoice]) {
        self.title = title
        self.symbol = symbol
        self.hint = hint
        self.menu = menu
    }

    @ViewBuilder
    func view(titleSize: CGFloat?) -> some View {
        let label = BarLabel(title: title, symbol: symbol, titleSize: titleSize, hasMenu: menu != nil)
        Group {
            if let menu {
                Menu {
                    ForEach(menu, id: \.title) { choice in
                        if let symbol = choice.symbol {
                            Button(choice.title, systemImage: symbol, action: choice.action)
                        } else {
                            Button(choice.title, action: choice.action)
                        }
                    }
                } label: {
                    label
                }
                .accessibilityHint(hint)
            } else {
                Button(action: action) { label }
                    .buttonStyle(.plain)
            }
        }
        .tint(.primary)
        .accessibilityLabel(title)
        .accessibilityShowsLargeContentViewer {
            Label(title, systemImage: symbol)
        }
    }
}

/// An icon with its title underneath.
private struct BarLabel: View {
    let title: String
    let symbol: String
    let titleSize: CGFloat?
    let hasMenu: Bool
    @Environment(\.dynamicTypeSize) private var typeSize

    /// `titleSize` grown with the reader's text size, like the caption text style.
    private func scaled(_ size: CGFloat) -> CGFloat {
        UIFontMetrics(forTextStyle: .caption2)
            .scaledValue(for: size, compatibleWith: UITraitCollection(preferredContentSizeCategory: UIContentSizeCategory(typeSize)))
    }

    var body: some View {
        VStack(spacing: 3) {
            Image(systemName: symbol)
                .font(.system(size: 19, weight: .medium))
                .frame(height: 24)
            if let titleSize {
                HStack(spacing: 2) {
                    Text(title)
                    if hasMenu {
                        Image(systemName: "chevron.down")
                            .font(.system(size: titleSize * 0.65, weight: .bold))
                    }
                }
                .font(.system(size: scaled(titleSize), weight: .medium))
                .lineLimit(1)
                .fixedSize()
            }
        }
        .foregroundStyle(.primary)
        .frame(minWidth: 36, minHeight: 48)
        .padding(.vertical, 4)
        .contentShape(.rect)
    }
}

/// A round glass button at either end of the bar: close and Undo.
private struct RoundBarButton: View {
    let title: String
    let symbol: String
    let action: () -> Void
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 17, weight: .semibold))
                .frame(width: 46, height: 46)
                .contentShape(.circle)
                .opacity(isEnabled ? 1 : 0.35)
        }
        .buttonStyle(.plain)
        .glassEffect(.regular.interactive(), in: .circle)
        .accessibilityLabel(title)
        .accessibilityShowsLargeContentViewer {
            Label(title, systemImage: symbol)
        }
    }
}
