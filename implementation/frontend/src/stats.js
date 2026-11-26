// 管理台统计时间：只提交合法的可解析时间，统一为固定毫秒 UTC（左闭右开）。
// 合法时间的解析一致性由后端 #11 修复；前端不以此替代后端对 Z / +08:00 写法的处理。

const ISO_MS = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,3})?)?(Z|[+-]\d{2}:\d{2})?$/;
const DATE_HEAD = /^(\d{4})-(\d{2})-(\d{2})/;

function daysInMonth(year, month) {
  if (month === 2) return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0 ? 29 : 28;
  return [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1];
}

// 原输入中的年-月-日必须真实存在。Date 会把 2026-02-30 归一化成 3 月 2 日，
// 直接把归一化后的时刻当作查询条件会静默扩大或移动统计区间。
export function calendarDateExists(value) {
  const m = DATE_HEAD.exec(String(value));
  if (!m) return true; // 非“YYYY-MM-DD”前缀的写法交由 Date 解析结果判定
  const year = Number(m[1]), month = Number(m[2]), day = Number(m[3]);
  if (month < 1 || month > 12) return false;
  return day >= 1 && day <= daysInMonth(year, month);
}

export function parseTime(value) {
  if (value == null || value === '') return null;
  if (!calendarDateExists(value)) return null;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d;
}

// 无时区写法的 datetime-local 表示用户本地时间；统一补齐不带小数秒的秒级 ISO。
export function toIso(value) {
  const d = parseTime(value);
  return d ? d.toISOString() : '';
}

// 返回 { from, to, errors, ok }；errors 非空时调用方不得发请求。
export function statsQuery(from, to) {
  const errors = [];
  const f = parseTime(from), t = parseTime(to);
  if (from !== '' && from != null && !f) errors.push('成交开始时间格式不正确。');
  if (to !== '' && to != null && !t) errors.push('成交截止时间格式不正确。');
  if (f && t && f.getTime() >= t.getTime()) errors.push('成交截止时间必须晚于开始时间。');
  const p = new URLSearchParams();
  if (!errors.length) {
    if (f) p.set('from', toIso(from));
    if (t) p.set('to', toIso(to));
  }
  return { from: p.get('from') || '', to: p.get('to') || '', errors, ok: !errors.length };
}

export function isIso(value) {
  return !!value && ISO_MS.test(value);
}

// 争议结案/处理成功后需要刷新的用户数据；通知与争议结果在其中。
export function refreshAfterManage(kind) {
  if (kind === 'dispute') return ['orders', 'disputes', 'notifications', 'inbox', 'admin'];
  return ['notifications', 'inbox', 'admin'];
}
