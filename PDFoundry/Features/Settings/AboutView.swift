import SwiftUI

struct AboutView: View {
    var body: some View {
        List {
            Section {
                BrandPanel()
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            }

            Section {
                LinkRow(title: "Help", symbol: "questionmark.circle", url: AppInfo.help)
                LinkRow(title: "Send Feedback", symbol: "bubble.left.and.text.bubble.right", url: AppInfo.feedback)
                if let review = AppInfo.reviewURL {
                    LinkRow(title: "Rate \(AppInfo.name)", symbol: "star", url: review)
                }
            }

            Section {
                LinkRow(title: "Website", symbol: "globe", detail: AppInfo.website.host(), url: AppInfo.website)
                LinkRow(title: "Privacy Policy", symbol: "hand.raised", url: AppInfo.privacy)
            } footer: {
                Text("Open source under the MIT License.")
                    .frame(maxWidth: .infinity)
                    .multilineTextAlignment(.center)
                    .padding(.top, 16)
            }
        }
        .navigationTitle("About \(AppInfo.name)")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// The one bold element on About: the app's mark on its dark brand colour.
private struct BrandPanel: View {
    private var year: String { Date.now.formatted(.dateTime.year()) }

    var body: some View {
        VStack(spacing: 10) {
            Image("BrandLogo")
                .resizable()
                .frame(width: 92, height: 92)
                .clipShape(.rect(cornerRadius: 21))
                .overlay { RoundedRectangle(cornerRadius: 21).strokeBorder(.white.opacity(0.18)) }
                .shadow(color: .black.opacity(0.45), radius: 16, y: 8)
                .padding(.bottom, 6)
                .accessibilityHidden(true)

            Text(AppInfo.name)
                .font(.system(size: 30, weight: .bold))
                .foregroundStyle(.white)

            Text("© \(year) Fahed Alhammadi. All rights reserved.")
                .font(.footnote)
                .foregroundStyle(.white.opacity(0.7))
                .multilineTextAlignment(.center)

            Text(AppInfo.version)
                .font(.caption.weight(.semibold))
                .foregroundStyle(Brand.emberLight)
                .padding(.horizontal, 12)
                .padding(.vertical, 5)
                .background(.white.opacity(0.12), in: .capsule)
                .padding(.top, 4)
        }
        .padding(.vertical, 34)
        .padding(.horizontal, 20)
        .frame(maxWidth: .infinity)
        .background {
            ZStack {
                LinearGradient(colors: [Brand.graphite, Brand.steel], startPoint: .topLeading, endPoint: .bottomTrailing)
                // Furnace glow from below, with a faint oversized page as the brand motif.
                RadialGradient(colors: [Brand.ember.opacity(0.55), .clear], center: .bottom, startRadius: 0, endRadius: 260)
                Image(systemName: "doc.fill")
                    .font(.system(size: 250))
                    .foregroundStyle(.white.opacity(0.04))
                    .rotationEffect(.degrees(-14))
                    .offset(x: 120, y: 30)
            }
        }
        .clipShape(.rect(cornerRadius: 24))
        .environment(\.colorScheme, .dark)
    }
}

/// A row that opens a page outside the app.
private struct LinkRow: View {
    let title: String
    let symbol: String
    var detail: String?
    let url: URL

    var body: some View {
        Link(destination: url) {
            HStack(spacing: 14) {
                Image(systemName: symbol)
                    .font(.body.weight(.medium))
                    .foregroundStyle(Color.accentColor)
                    .frame(width: 26)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .foregroundStyle(Color.primary)
                    if let detail {
                        Text(detail)
                            .font(.footnote)
                            .foregroundStyle(Color.secondary)
                    }
                }
                Spacer()
                Image(systemName: "arrow.up.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color(.tertiaryLabel))
            }
        }
        .accessibilityHint("Opens outside the app")
    }
}
