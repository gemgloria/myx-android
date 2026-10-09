import Foundation
import JavaScriptCore

enum SpreadsheetService {
    private struct SpreadsheetCourse: Decodable {
        var name: String
        var teacher: String
        var location: String
        var weekday: Int
        var startPeriod: Int
        var endPeriod: Int
        var weeks: [Int]
        var color: CourseColor
        var note: String
        var sourceText: String
        var warnings: [String]
        var candidate: ImportCandidate {
            .init(course: Course(name: name, teacher: teacher, location: location, weekday: weekday,
                                 startPeriod: startPeriod, endPeriod: endPeriod, weeks: weeks, color: color,
                                 note: note, sourceText: sourceText), confidence: 1, warnings: warnings)
        }
    }
    private struct SpreadsheetResult: Decodable {
        var courses: [SpreadsheetCourse]
        var warnings: [String]
        var suggestedSemesterName: String?
        var duplicateCount: Int
    }

    static func recognize(data: Data) async throws -> ImportResult {
        guard data.count <= 10 * 1024 * 1024 else { throw ScheduleError.invalid("文件过大，请只导出个人课表，大小不超过 10 MB。") }
        return try await Task.detached(priority: .userInitiated) {
            guard let library = Bundle.main.url(forResource: "xlsx.full.min", withExtension: "js"),
                  let importer = Bundle.main.url(forResource: "SpreadsheetImport", withExtension: "js"),
                  let context = JSContext() else { throw ScheduleError.invalid("表格导入组件未正确打包。") }
            var scriptError: String?
            context.exceptionHandler = { _, error in scriptError = error?.toString() }
            context.evaluateScript("var global = this; var console = {log:function(){},warn:function(){},error:function(){}};")
            context.evaluateScript(try String(contentsOf: library, encoding: .utf8))
            context.evaluateScript(try String(contentsOf: importer, encoding: .utf8))
            guard scriptError == nil else { throw ScheduleError.invalid(scriptError!) }
            let function = context.objectForKeyedSubscript("ClearClassSpreadsheet")?.objectForKeyedSubscript("importWorkbook")
            // Pass data as an argument. Never concatenate file content into executable JS.
            guard let output = function?.call(withArguments: [data.base64EncodedString()])?.toString(),
                  scriptError == nil else { throw ScheduleError.invalid(scriptError ?? "无法读取表格，请确认文件未加密。") }
            let result = try JSONDecoder().decode(SpreadsheetResult.self, from: Data(output.utf8))
            return ImportResult(candidates: result.courses.map(\.candidate), suggestedSemesterName: result.suggestedSemesterName,
                                warnings: result.warnings, duplicateCount: result.duplicateCount)
        }.value
    }
}
