import SwiftUI

struct ReceiveView: View {
    @EnvironmentObject private var model: AppModel
    let onOpenSettings: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 14) {
                    networkCard

                    if let preparation = model.sendingPreparation {
                        PreparationCard(preparation: preparation)
                    }

                    if model.transfers.isEmpty && model.sendingPreparation == nil {
                        emptyState
                    } else {
                        ForEach(model.transfers.prefix(12)) { transfer in
                            TransferCard(
                                transfer: transfer,
                                receivedURL: model.receivedURL(for: transfer),
                                onCancel: { model.cancel(transfer) },
                                onRetry: { model.retry(transfer) }
                            )
                        }
                    }

                    if let notice = model.notice, !notice.isEmpty {
                        Label(notice, systemImage: "info.circle")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 4)
                    }
                }
                .padding()
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("传输")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: onOpenSettings) {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityLabel("设置")
                }
            }
        }
    }

    private var networkCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("准备接收", systemImage: "dot.radiowaves.left.and.right")
                .font(.headline)
                .foregroundStyle(.indigo)
            Text(model.localIPAddresses.isEmpty
                 ? "未检测到可用的局域网连接"
                 : "\(model.deviceName) · \(model.localIPAddresses.joined(separator: ", "))")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text("端口 4061")
                .font(.caption.monospaced())
                .foregroundStyle(.tertiary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardStyle()
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "tray")
                .font(.system(size: 42, weight: .light))
                .foregroundStyle(.secondary)
            Text("还没有传输记录")
                .font(.headline)
            Text("从发送页选择一台设备，或等待其他设备发来文件。")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 48)
    }
}

private struct PreparationCard: View {
    let preparation: SendingPreparation

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(preparation.fileName)
                        .font(.headline)
                        .lineLimit(1)
                    Text("正在为 \(preparation.targetName) 准备文件")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                ProgressView()
            }
            ProgressView()
                .progressViewStyle(.linear)
        }
        .cardStyle()
    }
}

private struct TransferCard: View {
    let transfer: TransferSnapshot
    let receivedURL: URL?
    let onCancel: () -> Void
    let onRetry: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: transfer.direction == "send" ? "arrow.up.circle.fill" : "arrow.down.circle.fill")
                    .font(.title2)
                    .foregroundStyle(statusColor)

                VStack(alignment: .leading, spacing: 4) {
                    Text(transfer.fileName)
                        .font(.headline)
                        .lineLimit(2)
                    Text(transfer.localizedState)
                        .font(.caption)
                        .foregroundStyle(statusColor)
                }
                Spacer()
                Text("\(Int((transfer.progress * 100).rounded()))%")
                    .font(.headline.monospacedDigit())
            }

            SegmentedProgressView(transfer: transfer)
                .frame(height: 8)

            HStack {
                Text("\(Formatters.byteCount(transfer.projectedBytes)) / \(Formatters.byteCount(transfer.projectedTotal))")
                Spacer()
                if transfer.speedBytesPerSecond > 0, transfer.isActive {
                    Text(Formatters.speed(transfer.speedBytesPerSecond))
                }
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(.secondary)

            if transfer.isActive && transfer.targetWorkers > 0 {
                Text("并发 \(transfer.activeWorkers)/\(transfer.allocatedWorkers ?? transfer.activeWorkers) · 目标 \(transfer.targetWorkers)")
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.tertiary)
            }

            if let message = transfer.message,
               !message.isEmpty,
               transfer.state == "failed" {
                Text(message)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            if transfer.isActive || transfer.state == "failed" || receivedURL != nil {
                Divider()
                HStack {
                    if transfer.isActive {
                        Button("取消", role: .destructive, action: onCancel)
                    } else if transfer.state == "failed" && transfer.direction == "send" {
                        Button("重试", action: onRetry)
                    }

                    Spacer()

                    if let receivedURL {
                        ShareLink(item: receivedURL) {
                            Label("分享文件", systemImage: "square.and.arrow.up")
                        }
                    }
                }
                .font(.subheadline.weight(.medium))
            }
        }
        .cardStyle()
    }

    private var statusColor: Color {
        switch transfer.state {
        case "completed": return .green
        case "failed", "cancelled": return .red
        default: return .indigo
        }
    }
}

private struct SegmentedProgressView: View {
    let transfer: TransferSnapshot

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(Color(uiColor: .tertiarySystemFill))

                if transfer.totalBytes > 0, let segments = transfer.segments, !segments.isEmpty {
                    ForEach(Array(segments.enumerated()), id: \.offset) { entry in
                        let segment = entry.element
                        if segment.running {
                            interval(segment.start, segment.end, width: proxy.size.width)
                                .fill(Color.indigo.opacity(0.25))
                        }
                    }
                    ForEach(Array(segments.enumerated()), id: \.offset) { entry in
                        let segment = entry.element
                        interval(segment.start, segment.downloadedEnd, width: proxy.size.width)
                            .fill(Color.indigo)
                    }
                } else {
                    Capsule()
                        .fill(Color.indigo)
                        .frame(width: proxy.size.width * transfer.progress)
                }
            }
            .clipShape(Capsule())
        }
    }

    private func interval(_ start: UInt64, _ end: UInt64, width: CGFloat) -> Path {
        let total = max(1, transfer.totalBytes)
        let boundedStart = min(start, total)
        let boundedEnd = min(max(end, boundedStart), total)
        let x = width * CGFloat(Double(boundedStart) / Double(total))
        let intervalWidth = width * CGFloat(Double(boundedEnd - boundedStart) / Double(total))
        return Path(CGRect(x: x, y: 0, width: intervalWidth, height: 8))
    }
}

private extension View {
    func cardStyle() -> some View {
        padding(16)
            .background(Color(uiColor: .secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}
