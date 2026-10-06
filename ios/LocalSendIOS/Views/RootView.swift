import SwiftUI

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @State private var isShowingSettings = false

    var body: some View {
        TabView(selection: $model.selectedTab) {
            SendView(onOpenSettings: { isShowingSettings = true })
                .tabItem {
                    Label("发送", systemImage: "paperplane.fill")
                }
                .tag(0)

            ReceiveView(onOpenSettings: { isShowingSettings = true })
                .tabItem {
                    Label("接收", systemImage: "tray.and.arrow.down.fill")
                }
                .tag(1)
        }
        .tint(.indigo)
        .sheet(isPresented: $isShowingSettings) {
            SettingsView()
        }
        .sheet(item: $model.incomingOffer) { offer in
            IncomingOfferView(
                offer: offer,
                onAccept: model.acceptIncomingOffer,
                onReject: model.rejectIncomingOffer
            )
            .interactiveDismissDisabled()
            .presentationDetents([.medium])
        }
        .alert(
            "提示",
            isPresented: Binding(
                get: { model.errorMessage != nil },
                set: { if !$0 { model.errorMessage = nil } }
            )
        ) {
            Button("好") { model.errorMessage = nil }
        } message: {
            Text(model.errorMessage ?? "未知错误")
        }
    }
}

private struct IncomingOfferView: View {
    let offer: IncomingOffer
    let onAccept: () -> Void
    let onReject: () -> Void

    var body: some View {
        VStack(spacing: 22) {
            Image(systemName: "arrow.down.doc.fill")
                .font(.system(size: 44, weight: .semibold))
                .foregroundStyle(.indigo)
                .padding(.top, 26)

            VStack(spacing: 8) {
                Text("收到文件")
                    .font(.title2.bold())
                Text(offer.fileName)
                    .font(.headline)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                Text("\(Formatters.byteCount(offer.fileSize)) · 来自 \(offer.senderIP)")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 12) {
                Button("拒绝", role: .destructive, action: onReject)
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)
                Button("接收", action: onAccept)
                    .buttonStyle(.borderedProminent)
                    .tint(.indigo)
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 24)

            Spacer(minLength: 16)
        }
    }
}
