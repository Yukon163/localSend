import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("设备") {
                    LabeledContent("设备名称", value: model.deviceName)
                    LabeledContent("发现端口", value: "4060")
                    LabeledContent("传输端口", value: "4061")
                }

                Section("接收文件") {
                    Text("接收完成的文件保存在本 App 的“文件”目录中，支持通过系统分享菜单保存到其他位置。")
                    ShareLink(item: model.receivedDirectory) {
                        Label("共享接收目录", systemImage: "folder")
                    }
                }

                Section("局域网") {
                    Text("设备发现和文件传输均在当前局域网内直连，不会上传到云端。首次使用时请允许访问本地网络。")
                }

                Section {
                    LabeledContent("iOS 版本", value: "1.0")
                    LabeledContent("传输核心", value: "Rust V3")
                }
            }
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }
}
