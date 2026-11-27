import PDFKit
import SwiftUI

/// What the Protect sheet asks for: the same password twice.
struct ProtectForm {
    var password = ""
    var confirm = ""

    static let warning = "If you forget the password, the file can't be opened, and it can't be recovered."

    var canProtect: Bool { !password.isEmpty && password == confirm }
    /// Shown once both fields have something in them but don't match.
    var mismatch: Bool { !password.isEmpty && !confirm.isEmpty && password != confirm }
}

/// Password-protect, from the Tools tab or the reader's More menu: the password twice and the warning. The file is
/// replaced only once both fields match and the user taps Protect. The password stays in this sheet's memory
/// until it is handed to `onProtect`; it is never saved anywhere.
struct ProtectSheet: View {
    let name: String
    /// The file to check first: an already protected file gets a message and nothing else. Nil from the reader,
    /// which only offers Protect for unprotected documents.
    var url: URL?
    /// Protects the file; returns an error message, or nil when it worked.
    let onProtect: (String) async -> String?

    @Environment(\.dismiss) private var dismiss
    @State private var form = ProtectForm()
    @State private var isChecking = true
    @State private var alreadyProtected = false
    @State private var isWorking = false
    @State private var errorMessage: String?
    @FocusState private var focus: Field?

    private enum Field { case password, confirm }

    var body: some View {
        NavigationStack {
            Group {
                if isChecking {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if alreadyProtected {
                    ContentUnavailableView {
                        Label("Already Protected", systemImage: "lock.doc.fill")
                    } description: {
                        Text("“\(name)” already has a password. To change it, open it, choose More › Remove Password, then protect it again.")
                    }
                } else {
                    formView
                }
            }
            .navigationTitle("Password-protect")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(alreadyProtected ? "Done" : "Cancel") { dismiss() }
                }
                if !alreadyProtected && !isChecking {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Protect", action: protect)
                            .disabled(!form.canProtect || isWorking)
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .task { await check() }
    }

    private var formView: some View {
        Form {
            Section {
                SecureField("Password", text: $form.password)
                    .textContentType(.newPassword)
                    .focused($focus, equals: .password)
                    .submitLabel(.next)
                    .onSubmit { focus = .confirm }
                SecureField("Confirm Password", text: $form.confirm)
                    .textContentType(.newPassword)
                    .focused($focus, equals: .confirm)
                    .submitLabel(.done)
                    .onSubmit { if form.canProtect { protect() } }
            } header: {
                Text("“\(name)”")
            } footer: {
                VStack(alignment: .leading, spacing: 6) {
                    if form.mismatch {
                        Text("The passwords don't match.").foregroundStyle(.red)
                    }
                    if let errorMessage {
                        Text(errorMessage).foregroundStyle(.red)
                    }
                    Label(ProtectForm.warning, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.secondary)
                }
            }
        }
        .onAppear { focus = .password }
    }

    private func check() async {
        defer { isChecking = false }
        guard let url else { return }
        let data = await DocumentStore.readCoordinated(url)
        alreadyProtected = data.flatMap(PDFDocument.init(data:))?.isEncrypted ?? false
    }

    private func protect() {
        guard form.canProtect else { return }
        isWorking = true
        let password = form.password
        Task {
            defer { isWorking = false }
            if let error = await onProtect(password) {
                errorMessage = error
            } else {
                form = ProtectForm()
                dismiss()
            }
        }
    }
}

/// Asks for the password of a protected file a tool reads (Split, Extract). The password is kept only in the
/// calling view's memory, for this one job.
struct PasswordPrompt: View {
    let name: String
    var error: String?
    let onUnlock: (String) -> Void

    @State private var password = ""

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "lock.doc.fill")
                .font(.system(size: 48))
                .foregroundStyle(.tint)
            Text("“\(name)” Is Locked")
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
            Text("Enter its password to use it. The new files keep the same password.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            SecureField("Password", text: $password)
                .textFieldStyle(.roundedBorder)
                .frame(maxWidth: 280)
                .onSubmit(unlock)
            if let error {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
            }
            Button("Unlock", action: unlock)
                .buttonStyle(.glassProminent)
                .disabled(password.isEmpty)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func unlock() {
        guard !password.isEmpty else { return }
        onUnlock(password)
        password = ""
    }
}
