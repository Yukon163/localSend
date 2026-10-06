import Foundation

private let localSendRustCallback: @convention(c) (UnsafeMutablePointer<CChar>?) -> Void = { pointer in
    guard let pointer else { return }
    let payload = String(cString: pointer)
    rust_flutter_free_string(pointer)
    DispatchQueue.main.async {
        RustBridge.shared.receive(payload)
    }
}

final class RustBridge {
    static let shared = RustBridge()

    var eventHandler: ((String) -> Void)?

    private init() {}

    fileprivate func receive(_ payload: String) {
        eventHandler?(payload)
    }

    func configureIdentity(id: String, name: String) -> Bool {
        id.withCString { idPointer in
            name.withCString { namePointer in
                rust_flutter_configure_device_identity(idPointer, namePointer)
            }
        }
    }

    func startDiscovery(name: String, port: UInt16 = 4060) {
        name.withCString { pointer in
            rust_flutter_start_discovery(port, pointer, localSendRustCallback)
        }
    }

    func discoverOnce(name: String, port: UInt16 = 4060) {
        name.withCString { pointer in
            rust_flutter_discover_once(port, pointer)
        }
    }

    func startFileServer(saveDirectory: URL, port: UInt16 = 4061) {
        saveDirectory.path.withCString { pointer in
            rust_flutter_start_file_server(port, pointer, localSendRustCallback)
        }
    }

    func sendFile(to ip: String, fileURL: URL, port: UInt16 = 4061) -> String? {
        ip.withCString { ipPointer in
            fileURL.path.withCString { pathPointer in
                guard let result = rust_flutter_send_file(
                    ipPointer,
                    port,
                    pathPointer,
                    8,
                    localSendRustCallback
                ) else {
                    return nil
                }
                let transferID = String(cString: result)
                rust_flutter_free_string(result)
                return transferID.isEmpty ? nil : transferID
            }
        }
    }

    func cancel(transferID: String) -> Bool {
        transferID.withCString { rust_flutter_cancel_transfer($0) }
    }

    func retry(transferID: String) -> Bool {
        transferID.withCString { rust_flutter_retry_transfer($0) }
    }

    func accept(offerID: String, destination: URL) -> Bool {
        offerID.withCString { offerPointer in
            destination.path.withCString { destinationPointer in
                rust_flutter_accept_transfer(offerPointer, destinationPointer)
            }
        }
    }

    func reject(offerID: String) -> Bool {
        offerID.withCString { rust_flutter_reject_transfer($0) }
    }

    func stopServer() {
        rust_flutter_stop_server()
    }
}
