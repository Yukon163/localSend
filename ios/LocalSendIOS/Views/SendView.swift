import SwiftUI
import UniformTypeIdentifiers

struct SendView: View {
    @EnvironmentObject private var model: AppModel
    let onOpenSettings: () -> Void

    var body: some View {
        NavigationStack {
            List {
                Section("本机") {
                    HStack(spacing: 12) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(Color.indigo.opacity(0.12))
                            Image(systemName: "iphone")
                                .font(.title2.weight(.semibold))
                                .foregroundStyle(.indigo)
                        }
                        .frame(width: 44, height: 44)

                        VStack(alignment: .leading, spacing: 3) {
                            Text(model.deviceName)
                                .font(.headline)
                            Text(model.localIPAddresses.isEmpty
                                 ? "未检测到局域网地址"
                                 : model.localIPAddresses.joined(separator: " · "))
                                .font(.caption)
                                .foregroundStyle(model.localIPAddresses.isEmpty ? Color.red : Color.secondary)
                        }
                    }
                    .padding(.vertical, 3)
                }

                Section {
                    if model.devices.isEmpty {
                        VStack(spacing: 10) {
                            Image(systemName: "wifi.slash")
                                .font(.system(size: 30))
                                .foregroundStyle(.secondary)
                            Text("暂未发现设备")
                                .font(.headline)
                            Text("确认两台设备连接同一 Wi-Fi，然后点右上角刷新。")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 30)
                        .listRowBackground(Color.clear)
                    } else {
                        ForEach(model.devices) { device in
                            Button {
                                model.chooseFile(for: device)
                            } label: {
                                DeviceRow(device: device)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                } header: {
                    Text("点击设备发送文件")
                }
            }
            .navigationTitle("LocalSend")
            .toolbar {
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    Button(action: model.refresh) {
                        Image(systemName: "arrow.clockwise")
                    }
                    .accessibilityLabel("刷新设备")

                    Button(action: onOpenSettings) {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityLabel("设置")
                }
            }
            .refreshable {
                model.refresh()
            }
            .fileImporter(
                isPresented: $model.isImportingFile,
                allowedContentTypes: [.item],
                allowsMultipleSelection: false
            ) { result in
                switch result {
                case .success(let urls):
                    if let url = urls.first { model.sendImportedFile(url) }
                case .failure(let error):
                    model.errorMessage = "无法选择文件：\(error.localizedDescription)"
                }
            }
        }
    }
}

private struct DeviceRow: View {
    let device: DiscoveredDevice

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(Color.indigo.opacity(0.12))
                Image(systemName: "laptopcomputer.and.iphone")
                    .foregroundStyle(.indigo)
            }
            .frame(width: 42, height: 42)

            VStack(alignment: .leading, spacing: 3) {
                Text(device.name)
                    .font(.headline)
                    .foregroundStyle(.primary)
                Text(device.ip)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
            }

            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption.bold())
                .foregroundStyle(.tertiary)
        }
        .contentShape(Rectangle())
        .padding(.vertical, 4)
    }
}
