/* ClearClass timetable importer. Runs offline in JavaScriptCore and Node tests. */
(function (root) {
  'use strict';
  var colors = ['violet', 'teal', 'blue', 'amber', 'rose', 'indigo'];
  function normalize(value) {
    return String(value == null ? '' : value).replace(/[！-～]/g, function (ch) {
      return String.fromCharCode(ch.charCodeAt(0) - 0xFEE0);
    }).replace(/[—–－~～至]/g, '-').replace(/[，、；;]/g, ',').replace(/\u3000/g, ' ');
  }
  function weeks(value) {
    var text = normalize(value).replace(/\s/g, ''), odd = /单/.test(text), even = /双/.test(text);
    if (odd && even) throw new Error('周次不能同时包含单周和双周');
    text = text.replace(/[\[\]()第周单双]/g, '');
    if (!text) throw new Error('周次为空');
    var result = {};
    text.split(',').forEach(function (part) {
      if (!/^\d+(?:-\d+)?$/.test(part)) throw new Error('无法解析周次：' + value);
      var p = part.split('-').map(Number), a = p[0], b = p.length === 1 ? a : p[1];
      if (a < 1 || b < a || b > 53) throw new Error('周次范围无效：' + value);
      for (var w = a; w <= b; ++w) if ((!odd || w % 2 === 1) && (!even || w % 2 === 0)) result[w] = true;
    });
    var out = Object.keys(result).map(Number).sort(function (a, b) { return a - b; });
    if (!out.length) throw new Error('周次范围内没有符合条件的周次');
    return out;
  }
  function periodRanges(value) {
    var text = normalize(value).replace(/[\[\]()第节\s]/g, ''), result = {};
    text.split(',').forEach(function (part) {
      if (!/^\d+(?:-\d+)*$/.test(part)) throw new Error('无法解析节次：' + value);
      var p = part.split('-').map(Number), a = p[0], b = p[p.length - 1];
      if (a < 1 || b < a || b > 16 || p.some(function (v, i) { return i > 0 && v < p[i - 1]; })) throw new Error('节次范围无效');
      for (var n = a; n <= b; ++n) result[n] = true;
    });
    var sorted = Object.keys(result).map(Number).sort(function (a, b) { return a - b; }), ranges = [];
    sorted.forEach(function (n) {
      var last = ranges[ranges.length - 1];
      if (last && n === last[1] + 1) last[1] = n; else ranges.push([n, n]);
    });
    if (!ranges.length) throw new Error('节次为空');
    return ranges;
  }
  function weekday(value) {
    var text = normalize(value).trim().replace(/^(星期|礼拜|周)/, '');
    var map = { '一': 1, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6, '日': 7, '天': 7,
      'Mon': 1, 'Tue': 2, 'Wed': 3, 'Thu': 4, 'Fri': 5, 'Sat': 6, 'Sun': 7 };
    var number = map[text] || Number(text);
    if (number < 1 || number > 7 || !Number.isInteger(number)) throw new Error('星期无法识别：' + value);
    return number;
  }
  function color(name) {
    var hash = 0;
    for (var i = 0; i < name.length; ++i) hash = (hash * 33 + name.charCodeAt(i)) >>> 0;
    return colors[hash % colors.length];
  }
  function course(name, teacher, location, day, periods, weekList, source, note) {
    if (!name.trim()) throw new Error('课程名称为空');
    return { name: name.trim(), teacher: teacher.trim(), location: location.trim(), weekday: day,
      startPeriod: periods[0], endPeriod: periods[1], weeks: weekList, color: color(name),
      note: note || '', sourceText: source, warnings: location.trim() ? [] : ['教室为空，请补充或核对'] };
  }
  function parseCell(value, day) {
    var text = normalize(value).replace(/\r\n?/g, '\n').trim();
    if (!text) return [];
    var regex = /\[([\d,\-\s]+)\]\s*节|\[([\d,\-\s]+)节\]/g, match, start = 0, out = [];
    while ((match = regex.exec(text))) {
      var block = text.slice(start, match.index).trim(); start = regex.lastIndex;
      var lines = block.split('\n').map(function (line) { return line.trim(); }).filter(Boolean);
      var wi = lines.findIndex(function (line) { return /^\d[\d,\-\s]*(?:[\[(](?:周|单周|双周)[\])]|周)(?:单|双)?$/.test(line); });
      if (wi < 1) throw new Error('课程缺少有效周次：' + block.slice(0, 40));
      var head = lines.slice(0, wi), teacher = head.length > 1 ? head.pop() : '';
      var name = head.join(''), note = '';
      if (/[PO]$/.test(name)) {
        note = /P$/.test(name) ? '表格标记 P：部分调课，请核对。' : '表格标记 O：整体调课，请核对。';
        name = name.slice(0, -1);
      }
      var weekList = weeks(lines[wi]), room = lines.slice(wi + 1).join('');
      periodRanges(match[1] || match[2]).forEach(function (range) {
        out.push(course(name, teacher, room, day, range, weekList, block + '\n' + match[0], note));
      });
    }
    if (!out.length && text) throw new Error('课程缺少节次，未导入：' + text.slice(0, 40));
    return out;
  }
  function parseSheets(sheets) {
    var all = [], warnings = [], semesterName = null;
    sheets.forEach(function (sheet) {
      var rows = sheet.rows, headerIndex = -1, columns = {};
      rows.slice(0, 25).forEach(function (row, ri) {
        var joined = row.join(' '), term = normalize(joined).match(/(20\d{2})-(20\d{2})-([12])/);
        if (term && !semesterName) semesterName = term[1] + '–' + term[2] + ' 第' + term[3] + '学期';
        if (headerIndex >= 0) return;
        var candidate = {};
        row.forEach(function (cell, ci) {
          if (/^(星期|周)[一二三四五六日天]$/.test(String(cell).trim())) candidate[ci] = weekday(cell);
        });
        if (Object.keys(candidate).length >= 5) { headerIndex = ri; columns = candidate; }
      });
      if (headerIndex >= 0) {
        rows.slice(headerIndex + 1).forEach(function (row, offset) {
          if (row.some(function (cell) { return /^\s*备注[:：]/.test(String(cell)); })) {
            var remarks = row.filter(Boolean).join(' ').trim();
            warnings.push('以下备注没有固定星期或节次，未排入周课表：' + remarks);
            return;
          }
          Object.keys(columns).forEach(function (key) {
            var value = row[Number(key)];
            if (!value || !String(value).trim()) return;
            try { all = all.concat(parseCell(value, columns[key])); }
            catch (error) { warnings.push(sheet.name + ' 第' + (headerIndex + offset + 2) + '行：' + error.message); }
          });
        });
      } else {
        // Also accept one-course-per-row exports with explicit columns.
        var aliases = { name: ['课程', '课程名称', '名称', 'course'], teacher: ['教师', '任课教师', '老师', 'teacher'],
          location: ['教室', '上课地点', '地点', 'location'], weekday: ['星期', '上课星期', '星期几', 'weekday'],
          periods: ['节次', '上课节次', 'periods'], start: ['开始节次', '起始节次', 'startPeriod'],
          end: ['结束节次', 'endPeriod'], weeks: ['周次', '上课周次', 'weeks'] };
        var map = {}, listHeader = -1;
        rows.slice(0, 25).some(function (row, ri) {
          var candidate = {};
          Object.keys(aliases).forEach(function (field) {
            var ci = row.findIndex(function (cell) { return aliases[field].indexOf(String(cell).trim()) >= 0; });
            if (ci >= 0) candidate[field] = ci;
          });
          if (candidate.name != null && candidate.weekday != null && candidate.weeks != null &&
              (candidate.periods != null || candidate.start != null)) { map = candidate; listHeader = ri; return true; }
          return false;
        });
        if (listHeader < 0) { warnings.push(sheet.name + '：未找到星期表头或课程列，未导入此工作表。'); return; }
        rows.slice(listHeader + 1).forEach(function (row, offset) {
          function get(field) { return map[field] == null ? '' : String(row[map[field]] == null ? '' : row[map[field]]); }
          if (!get('name').trim()) return;
          try {
            var ranges = periodRanges(get('periods') || (get('start') + (get('end') ? '-' + get('end') : '')));
            var wl = weeks(get('weeks')), day = weekday(get('weekday'));
            ranges.forEach(function (range) { all.push(course(get('name'), get('teacher'), get('location'), day, range, wl, row.join('\t'))); });
          } catch (error) { warnings.push(sheet.name + ' 第' + (listHeader + offset + 2) + '行：' + error.message); }
        });
      }
    });
    var seen = Object.create(null), deduplicated = [];
    all.forEach(function (item) {
      var key = JSON.stringify([item.name, item.teacher, item.location, item.weekday, item.startPeriod, item.endPeriod, item.weeks]);
      if (!seen[key]) { seen[key] = true; deduplicated.push(item); }
    });
    if (!deduplicated.length) throw new Error(warnings[0] || '没有找到可导入的课程');
    return { courses: deduplicated, warnings: warnings, suggestedSemesterName: semesterName, duplicateCount: all.length - deduplicated.length };
  }
  function importWorkbook(base64) {
    var workbook = root.XLSX.read(base64, { type: 'base64', cellDates: false, sheetRows: 501 });
    var sheets = workbook.SheetNames.slice(0, 10).map(function (name) {
      var rows = root.XLSX.utils.sheet_to_json(workbook.Sheets[name], { header: 1, raw: false, defval: '', blankrows: true });
      if (rows.length > 500 || rows.some(function (r) { return r.length > 200; })) throw new Error('表格过大，请只导出个人课表');
      return { name: name, rows: rows };
    });
    return JSON.stringify(parseSheets(sheets));
  }
  root.ClearClassSpreadsheet = { parseSheets: parseSheets, parseCell: parseCell, parseWeeks: weeks, parsePeriods: periodRanges, importWorkbook: importWorkbook };
})(typeof globalThis !== 'undefined' ? globalThis : this);
