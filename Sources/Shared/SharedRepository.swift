import Foundation

enum SharedRepository {
    static var groupID: String {
        Bundle.main.object(forInfoDictionaryKey: "ClearClassAppGroup") as? String ?? "group.com.mayexin.ClearClass"
    }
    static var sharedContainerAvailable: Bool {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: groupID) != nil
    }
    static func fileURL(requireShared: Bool = false) throws -> URL {
        if let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: groupID) {
            return container.appendingPathComponent("schedule-v1.json")
        }
        if requireShared { throw ScheduleError.invalid("小组件共享未启用。") }
        let local = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                appropriateFor: nil, create: true).appendingPathComponent("ClearClass")
        try FileManager.default.createDirectory(at: local, withIntermediateDirectories: true)
        return local.appendingPathComponent("schedule-v1.json")
    }
    static func decode(_ data: Data) throws -> ScheduleState {
        guard data.count <= 10 * 1024 * 1024 else { throw ScheduleError.invalid("备份文件过大。") }
        let state = try JSONDecoder().decode(ScheduleState.self, from: data)
        try state.validate()
        return state
    }
    static func encode(_ state: ScheduleState) throws -> Data {
        try state.validate()
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(state)
    }
    static func load(requireShared: Bool = false) throws -> ScheduleState? {
        let url = try fileURL(requireShared: requireShared)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        return try decode(Data(contentsOf: url))
    }
    static func loadBackup() throws -> ScheduleState? {
        let backup = try fileURL().appendingPathExtension("bak")
        guard FileManager.default.fileExists(atPath: backup.path) else { return nil }
        return try decode(Data(contentsOf: backup))
    }
    static func save(_ state: ScheduleState) throws {
        let data = try encode(state)
        let url = try fileURL()
        if let previous = try? Data(contentsOf: url), (try? decode(previous)) != nil {
            try previous.write(to: url.appendingPathExtension("bak"), options: .atomic)
        }
        try data.write(to: url, options: .atomic)
    }
}
