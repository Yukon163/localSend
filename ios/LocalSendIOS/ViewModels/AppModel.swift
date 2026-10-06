import Foundation
import SwiftUI
import UIKit

@MainActor
final class AppModel: ObservableObject {
    @Published private(set) var devices: [DiscoveredDevice] = []
    @Published private(set) var transfers: [TransferSnapshot] = []
    @Published private(set) var localIPAddresses: [String] = []
    @Published private(set) var deviceName: String
    @Published var selectedTab = 0
    @Published var isImportingFile = false
    @Published var selectedDevice: DiscoveredDevice?
    @Published var incomingOffer: IncomingOffer?
    @Published var sendingPreparation: SendingPreparation?
    @Published var notice: String?
    @Published var errorMessage: String?

    private let bridge = RustBridge.shared
    private let fileManager = FileManager.default
    private var hasStartedServices = false
    private var queuedOffers: [IncomingOffer] = []
    private var transferByID: [String: TransferSnapshot] = [:]
    private var updatedAtByID: [String: Date] = [:]
    private var outgoingFiles: [String: URL] = [:]
    private var outgoingTargets: [String: String] = [:]

    private(set) lazy var receivedDirectory: URL = {
        let documents = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return documents.appendingPathComponent("Received", isDirectory: true)
    }()

    private lazy var outgoingDirectory: URL = {
        let support = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return support.appendingPathComponent("Outgoing", isDirectory: true)
    }()

    init() {
        let defaults = UserDefaults.standard
        if let saved = defaults.string(forKey: "deviceName"), !saved.isEmpty {
            deviceName = saved
        } else {
            let generated = "iPhone-\(Int.random(in: 1000...9999))"
            defaults.set(generated, forKey: "deviceName")
            deviceName = generated
        }

        bridge.eventHandler = { [weak self] payload in
            self?.handlePayload(payload)
        }
    }

    func startServicesIfNeeded() {
        guard !hasStartedServices else { return }
        hasStartedServices = true

        do {
            try fileManager.createDirectory(at: receivedDirectory, withIntermediateDirectories: true)
            try fileManager.createDirectory(at: outgoingDirectory, withIntermediateDirectories: true)
        } catch {
            errorMessage = "无法创建应用文件目录：\(error.localizedDescription)"
            return
        }

        let defaults = UserDefaults.standard
        let deviceID: String
        if let saved = defaults.string(forKey: "deviceID"), !saved.isEmpty {
            deviceID = saved
        } else {
            deviceID = UUID().uuidString.lowercased()
            defaults.set(deviceID, forKey: "deviceID")
        }

        guard bridge.configureIdentity(id: deviceID, name: deviceName) else {
            errorMessage = "无法初始化设备身份"
            return
        }

        bridge.startDiscovery(name: deviceName)
        bridge.startFileServer(saveDirectory: receivedDirectory)
        refresh()
    }

    func refresh() {
        localIPAddresses = NetworkAddresses.localIPv4Addresses()
        devices.removeAll()
        bridge.discoverOnce(name: deviceName)
    }

    func chooseFile(for device: DiscoveredDevice) {
        selectedDevice = device
        isImportingFile = true
    }

    func sendImportedFile(_ sourceURL: URL) {
        guard let target = selectedDevice else { return }
        selectedDevice = nil
        sendingPreparation = SendingPreparation(fileName: sourceURL.lastPathComponent, targetName: target.name)
        selectedTab = 1
        let stagingRoot = outgoingDirectory

        Task {
            do {
                let stagedURL = try await Task.detached(priority: .userInitiated) {
                    try Self.stageOutgoingFile(
                        sourceURL,
                        under: stagingRoot,
                        fileManager: FileManager.default
                    )
                }.value

                guard let transferID = bridge.sendFile(to: target.ip, fileURL: stagedURL) else {
                    try? fileManager.removeItem(at: stagedURL.deletingLastPathComponent())
                    throw LocalSendError.couldNotStartTransfer
                }
                outgoingFiles[transferID] = stagedURL
                outgoingTargets[transferID] = target.ip
                sendingPreparation = nil
            } catch {
                sendingPreparation = nil
                errorMessage = "无法准备文件：\(error.localizedDescription)"
            }
        }
    }

    func acceptIncomingOffer() {
        guard let offer = incomingOffer else { return }
        if !bridge.accept(offerID: offer.id, destination: receivedDirectory) {
            errorMessage = "接收请求已失效，请让对方重新发送"
        }
        advanceOfferQueue()
        selectedTab = 1
    }

    func rejectIncomingOffer() {
        guard let offer = incomingOffer else { return }
        _ = bridge.reject(offerID: offer.id)
        advanceOfferQueue()
    }

    func cancel(_ transfer: TransferSnapshot) {
        if !bridge.cancel(transferID: transfer.transferId) {
            errorMessage = "此传输已结束，无法取消"
        }
    }

    func retry(_ transfer: TransferSnapshot) {
        if bridge.retry(transferID: transfer.transferId) {
            return
        }
        guard let fileURL = outgoingFiles[transfer.transferId],
              let targetIP = outgoingTargets[transfer.transferId],
              fileManager.fileExists(atPath: fileURL.path),
              let newID = bridge.sendFile(to: targetIP, fileURL: fileURL) else {
            errorMessage = "无法重试：原文件或目标设备已不可用"
            return
        }
        outgoingFiles[newID] = fileURL
        outgoingTargets[newID] = targetIP
    }

    func receivedURL(for transfer: TransferSnapshot) -> URL? {
        guard transfer.direction == "receive", transfer.state == "completed" else { return nil }
        let finalName = (transfer.message?.isEmpty == false ? transfer.message! : transfer.fileName)
        let safeName = URL(fileURLWithPath: finalName).lastPathComponent
        let candidate = receivedDirectory.appendingPathComponent(safeName)
        return fileManager.fileExists(atPath: candidate.path) ? candidate : nil
    }

    private func handlePayload(_ payload: String) {
        guard let data = payload.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = object["type"] as? String else {
            return
        }

        switch type {
        case "deviceFound":
            guard let id = object["deviceId"] as? String,
                  let name = object["name"] as? String,
                  let ip = object["ip"] as? String else { return }
            let port = (object["controlPort"] as? NSNumber)?.uint16Value ?? 4060
            let device = DiscoveredDevice(id: id, name: name, ip: ip, controlPort: port)
            if let index = devices.firstIndex(where: { $0.ip == ip }) {
                devices[index] = device
            } else {
                devices.append(device)
                devices.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
            }

        case "receiveRequest":
            guard let fileName = object["fileName"] as? String else { return }
            let fileSize = (object["fileSize"] as? NSNumber)?.uint64Value ?? 0
            let senderIP = object["senderIp"] as? String ?? "未知设备"
            if let offerID = object["offerId"] as? String, !offerID.isEmpty {
                enqueue(
                    IncomingOffer(
                        id: offerID,
                        fileName: fileName,
                        fileSize: fileSize,
                        senderIP: senderIP
                    )
                )
            } else {
                notice = "正在从 \(senderIP) 接收 \(fileName)"
                selectedTab = 1
            }

        case "transferEvent":
            guard let eventObject = object["event"],
                  let eventData = try? JSONSerialization.data(withJSONObject: eventObject),
                  let snapshot = try? JSONDecoder().decode(TransferSnapshot.self, from: eventData) else {
                return
            }
            apply(snapshot)

        case "complete":
            let success = object["success"] as? Bool ?? false
            notice = success ? "传输完成" : (object["message"] as? String ?? "传输失败")

        default:
            break
        }
    }

    private func apply(_ snapshot: TransferSnapshot) {
        transferByID[snapshot.transferId] = snapshot
        updatedAtByID[snapshot.transferId] = Date()
        transfers = transferByID.values.sorted {
            (updatedAtByID[$0.transferId] ?? .distantPast) > (updatedAtByID[$1.transferId] ?? .distantPast)
        }
        sendingPreparation = nil

        if snapshot.isActive || snapshot.direction == "receive" {
            selectedTab = 1
        }
        if snapshot.state == "completed" || snapshot.state == "cancelled" {
            cleanupOutgoingFile(for: snapshot.transferId)
        }
        if snapshot.state == "failed" {
            notice = snapshot.message ?? snapshot.errorCode ?? "传输失败"
        }
    }

    private func enqueue(_ offer: IncomingOffer) {
        if incomingOffer == nil {
            incomingOffer = offer
        } else if incomingOffer?.id != offer.id && !queuedOffers.contains(where: { $0.id == offer.id }) {
            queuedOffers.append(offer)
        }
    }

    private func advanceOfferQueue() {
        incomingOffer = queuedOffers.isEmpty ? nil : queuedOffers.removeFirst()
    }

    private func cleanupOutgoingFile(for transferID: String) {
        guard let fileURL = outgoingFiles.removeValue(forKey: transferID) else { return }
        outgoingTargets.removeValue(forKey: transferID)
        try? fileManager.removeItem(at: fileURL.deletingLastPathComponent())
    }

    nonisolated private static func stageOutgoingFile(
        _ sourceURL: URL,
        under outgoingDirectory: URL,
        fileManager: FileManager
    ) throws -> URL {
        let granted = sourceURL.startAccessingSecurityScopedResource()
        defer {
            if granted { sourceURL.stopAccessingSecurityScopedResource() }
        }

        let transferDirectory = outgoingDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try fileManager.createDirectory(at: transferDirectory, withIntermediateDirectories: true)
        let safeName = sourceURL.lastPathComponent.isEmpty ? "file" : sourceURL.lastPathComponent
        let destination = transferDirectory.appendingPathComponent(safeName)

        do {
            try fileManager.copyItem(at: sourceURL, to: destination)
            return destination
        } catch {
            try? fileManager.removeItem(at: transferDirectory)
            throw error
        }
    }
}

private enum LocalSendError: LocalizedError {
    case couldNotStartTransfer

    var errorDescription: String? {
        switch self {
        case .couldNotStartTransfer: return "Rust 传输服务未能启动"
        }
    }
}
