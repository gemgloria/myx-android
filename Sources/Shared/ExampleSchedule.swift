import Foundation

enum ExampleSchedule {
    // Transcribed reference data, never presented as an OCR accuracy result.
    static func semester() -> Semester {
        var semester = Semester(name: "2026–2027 第1学期 · 示例", firstMonday: "2026-08-31")
        func add(_ name: String, _ teacher: String, _ room: String, _ day: Int, _ first: Int, _ last: Int, _ weeks: String, _ color: CourseColor) {
            semester.courses.append(Course(name: name, teacher: teacher, location: room, weekday: day,
                                           startPeriod: first, endPeriod: last, weeks: try! WeekExpression.parse(weeks), color: color))
        }
        add("概率论与数理统计 C", "许武玲", "教三南102", 1, 1, 2, "1-16", .violet)
        add("综合能源系统与技术", "胡静,李凡,刘慧,陈志杰", "教三北413", 2, 1, 2, "1-8", .teal)
        add("大数据技术及能源大数据", "邹雪", "教三北413", 3, 1, 2, "1-4,6-8", .blue)
        add("太阳能利用概论", "李海金,王健敏,汪波", "教三北413", 4, 1, 2, "1-10", .amber)
        add("新能源专业英语", "毛可可", "教三北413", 4, 1, 2, "11-18", .indigo)
        add("传热学", "汪冬冬", "教三北112", 5, 1, 2, "1-14", .rose)
        add("高等数学 A1", "李小伟", "东教一北105", 6, 1, 4, "4-18", .violet)
        add("电工学 2", "连马俊", "东教D207", 7, 1, 4, "4-11", .teal)
        add("大数据技术及能源大数据", "邹雪", "教三北413", 1, 3, 4, "1-8", .blue)
        add("太阳能利用概论", "李海金,王健敏,汪波", "教三北413", 2, 3, 4, "1-10", .amber)
        add("新能源专业英语", "毛可可", "教三北413", 2, 3, 4, "11-18", .indigo)
        add("传热学", "汪冬冬", "教三北112", 3, 3, 4, "1-14", .rose)
        add("大数据技术及能源大数据", "邹雪", "教一107", 3, 3, 4, "6", .blue)
        semester.courses[12].note = "截图标记 P：部分调课。第6周与传热学重叠，请向教务处核对。"
        add("综合能源系统与技术", "胡静,李凡,刘慧,陈志杰", "教三北413", 4, 3, 4, "1-8", .teal)
        add("物理实验", "孙云", "教二4、5楼物理实验室", 2, 5, 6, "1-18", .rose)
        add("新能源材料基础", "胡静,何一涛", "教三北413", 3, 9, 11, "1-4,6-14", .indigo)
        add("大学生职业发展与就业指导7", "王稳定,贾丽", "教三南203", 4, 9, 11, "1-2", .amber)
        add("电工学实验2（分组07）", "彭顺风", "教一701", 5, 9, 12, "3,7,9,11,14,16", .teal)
        return semester
    }
}
