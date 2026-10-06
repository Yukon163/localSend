import Foundation

struct DiscoveredDevice: Identifiable, Hashable {
    let id: String
    let name: String
    let ip: String
    let controlPort: UInt16
}

struct IncomingOffer: Identifiable, Equatable {
    let id: String
    let fileName: String
    let fileSize: UInt64
    let senderIP: String
}

struct TransferSegment: Codable, Hashable {
    let start: UInt64
    let end: UInt64
    let downloadedEnd: UInt64
    let running: Bool
}

struct TransferDiagnostics: Codable, Hashable {
    let hashingMs: UInt64?
    let negotiatingMs: UInt64?
    let transferringMs: UInt64?
    let verifyingMs: UInt64?
    let wireBytes: UInt64?
    let retransmittedBytes: UInt64?
    let splitTrials: UInt32?
    let rollbacks: UInt32?
    let retries: UInt32?
    let timeoutsOrResets: UInt32?
}

struct TransferSnapshot: Codable, Identifiable, Hashable {
    var id: String { transferId }

    let transferId: String
    let direction: String
    let state: String
    let fileName: String
    let durableBytes: UInt64
    let inFlightBytes: UInt64
    let totalBytes: UInt64
    let activeWorkers: UInt16
    let allocatedWorkers: UInt16?
    let targetWorkers: UInt16
    let speedBytesPerSecond: Double
    let elapsedMs: UInt64
    let stageBytes: UInt64?
    let stageTotalBytes: UInt64?
    let segments: [TransferSegment]?
    let diagnostics: TransferDiagnostics?
    let errorCode: String?
    let message: String?

    var isTerminal: Bool {
        state == "completed" || state == "failed" || state == "cancelled"
    }

    var isActive: Bool { !isTerminal }

    var projectedBytes: UInt64 {
        if state == "hashing", let stageBytes, (stageTotalBytes ?? 0) > 0 {
            return stageBytes
        }
        if state == "transferring" || state == "paused" {
            return min(totalBytes, durableBytes.saturatingAdding(inFlightBytes))
        }
        return durableBytes
    }

    var projectedTotal: UInt64 {
        if state == "hashing", let stageTotalBytes, stageTotalBytes > 0 {
            return stageTotalBytes
        }
        return totalBytes
    }

    var progress: Double {
        guard projectedTotal > 0 else { return state == "completed" ? 1 : 0 }
        return min(1, max(0, Double(projectedBytes) / Double(projectedTotal)))
    }

    var localizedState: String {
        switch state {
        case "offered": return "等待确认"
        case "hashing": return "正在校验文件"
        case "negotiating": return "正在协商连接"
        case "transferring": return direction == "send" ? "正在发送" : "正在接收"
        case "paused": return "正在调整并发"
        case "verifying": return "正在验证完整性"
        case "completed": return "传输成功"
        case "cancelled": return "已取消"
        case "failed": return "传输失败"
        default: return state
        }
    }
}

struct SendingPreparation: Equatable {
    let fileName: String
    let targetName: String
}

extension UInt64 {
    fileprivate func saturatingAdding(_ other: UInt64) -> UInt64 {
        let (result, overflow) = addingReportingOverflow(other)
        return overflow ? .max : result
    }
}

enum Formatters {
    static let bytes: ByteCountFormatter = {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useAll]
        formatter.countStyle = .file
        return formatter
    }()

    static func byteCount(_ value: UInt64) -> String {
        bytes.string(fromByteCount: Int64(min(value, UInt64(Int64.max))))
    }

    static func speed(_ value: Double) -> String {
        guard value > 0 else { return "0 B/s" }
        return "\(bytes.string(fromByteCount: Int64(value.rounded()))) /s"
    }
}
