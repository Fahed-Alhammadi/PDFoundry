import SwiftUI

@main
struct PDFoundryApp: App {
    @State private var store = DocumentStore()
    @State private var signatures = SignatureStore()
    @State private var initials = SignatureStore(kind: .initials)

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(store)
                .environment(signatures)
                .environment(\.initialsStore, initials)
        }
    }
}

struct RootView: View {
    private enum AppTab: Hashable { case documents, tools, settings }

    @Environment(DocumentStore.self) private var store
    @AppStorage(SettingsKey.appearance) private var appearance = Appearance.system
    @State private var tab = AppTab.documents

    var body: some View {
        TabView(selection: $tab) {
            Tab("Documents", systemImage: "doc.on.doc.fill", value: AppTab.documents) {
                LibraryView()
            }
            Tab("Tools", systemImage: "square.grid.2x2.fill", value: AppTab.tools) {
                ToolsView()
            }
            Tab("Settings", systemImage: "gearshape.fill", value: AppTab.settings) {
                SettingsView()
            }
        }
        .preferredColorScheme(appearance.colorScheme)
        .onOpenURL { url in
            tab = .documents
            store.openExternal(url)
        }
        // A tool on the Tools tab opened a document in the reader: the reader lives on the Documents tab.
        .onChange(of: store.pendingOpen) { _, url in
            if url != nil { tab = .documents }
        }
        .task { await store.start() }
    }
}
