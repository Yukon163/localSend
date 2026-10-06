import SwiftUI

@main
struct LocalSendIOSApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .task {
                    model.startServicesIfNeeded()
                }
        }
    }
}
