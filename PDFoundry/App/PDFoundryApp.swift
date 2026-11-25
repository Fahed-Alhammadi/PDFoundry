import SwiftUI

@main
struct PDFoundryApp: App {
    @State private var store = DocumentStore()
    @State private var signatures = SignatureStore()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(store)
                .environment(signatures)
        }
    }
}

struct RootView: View {
    private enum AppTab: Hashable { case documents, settings }

    @Environment(DocumentStore.self) private var store
    @AppStorage(SettingsKey.appearance) private var appearance = Appearance.system
    @State private var tab = AppTab.documents

    var body: some View {
        TabView(selection: $tab) {
            Tab("Documents", systemImage: "doc.on.doc.fill", value: AppTab.documents) {
                LibraryView()
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
        .task { await store.start() }
    }
}
