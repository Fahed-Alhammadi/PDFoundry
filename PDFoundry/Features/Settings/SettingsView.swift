import SwiftUI

struct SettingsView: View {
    @Environment(DocumentStore.self) private var store
    @Environment(SignatureStore.self) private var signatures
    @AppStorage(SettingsKey.appearance) private var appearance = Appearance.system
    @AppStorage(SettingsKey.horizontalScroll) private var horizontalScroll = false

    private var storageUsed: String {
        ByteCountFormatter.string(fromByteCount: store.items.reduce(0) { $0 + $1.size }, countStyle: .file)
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker(selection: $appearance) {
                        ForEach(Appearance.allCases) { Text($0.title).tag($0) }
                    } label: {
                        SettingsLabel("Appearance", symbol: "circle.lefthalf.filled", color: .indigo)
                    }
                    Toggle(isOn: $horizontalScroll) {
                        SettingsLabel("Scroll Pages Sideways", symbol: "arrow.left.and.right", color: .blue)
                    }
                }

                Section {
                    NavigationLink {
                        SavedSignaturesView()
                    } label: {
                        SettingsLabel("Signatures", symbol: "signature", color: Brand.ember)
                            .badge(signatures.signatures.count)
                    }
                    LabeledContent {
                        Text("\(store.items.count) · \(storageUsed)")
                    } label: {
                        SettingsLabel("Documents", symbol: "internaldrive.fill", color: .gray)
                    }
                } footer: {
                    Text("Your documents are stored on this device, in the PDFoundry folder of the Files app.")
                }

                Section {
                    NavigationLink {
                        AboutView()
                    } label: {
                        SettingsLabel("About \(AppInfo.name)", symbol: "info", color: Brand.steel)
                    }
                }
            }
            .navigationTitle("Settings")
        }
    }
}

/// A Settings row label: a white symbol on a coloured rounded tile.
struct SettingsLabel: View {
    let title: String
    let symbol: String
    let color: Color

    init(_ title: String, symbol: String, color: Color) {
        self.title = title
        self.symbol = symbol
        self.color = color
    }

    var body: some View {
        Label {
            Text(title)
        } icon: {
            Image(systemName: symbol)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 29, height: 29)
                .background(color.gradient, in: .rect(cornerRadius: 7))
        }
    }
}

private struct SavedSignaturesView: View {
    @Environment(SignatureStore.self) private var signatures
    @State private var showPad = false

    var body: some View {
        Group {
            if signatures.signatures.isEmpty {
                ContentUnavailableView {
                    Label("No Signatures", systemImage: "signature")
                } description: {
                    Text("Draw your signature once and reuse it in any document.")
                } actions: {
                    Button("New Signature") { showPad = true }
                        .buttonStyle(.glassProminent)
                }
            } else {
                SignatureList()
            }
        }
        .navigationTitle("Signatures")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("New Signature", systemImage: "plus") { showPad = true }
            }
        }
        .sheet(isPresented: $showPad) {
            NavigationStack {
                SignaturePad { _ in showPad = false }
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Cancel") { showPad = false }
                        }
                    }
            }
            .presentationDetents([.medium, .large])
        }
    }
}
