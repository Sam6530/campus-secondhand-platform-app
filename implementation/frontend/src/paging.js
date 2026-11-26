// “我的发布”分页响应规范化：后端返回 {items,page,size,total,totalPages}。
// 兼容旧数组响应（无分页字段时按单页处理）；页码越界（例如撤下最后一页最后一条）时回到最后一个有效页。
const DEFAULT_SIZE = 20;
const MAX_SIZE = 50;

export function pageParams(page, size = DEFAULT_SIZE) {
  const p = Math.max(1, Math.floor(Number(page) || 1));
  const raw = Math.floor(Number(size) || DEFAULT_SIZE);
  return { page: p, size: Math.min(MAX_SIZE, Math.max(1, raw)) };
}

export function pagingView(payload, requestedPage, requestedSize = DEFAULT_SIZE) {
  const req = pageParams(requestedPage, requestedSize);
  if (Array.isArray(payload)) {
    return { items: payload, page: 1, size: req.size, total: payload.length, totalPages: 1, legacy: true };
  }
  const items = payload && Array.isArray(payload.items) ? payload.items : [];
  const size = pageParams(1, payload && payload.size).size;
  const total = Math.max(0, Math.floor(Number(payload && payload.total) || 0));
  const totalPages = Math.max(1, Math.ceil(total / size));
  const page = Math.min(totalPages, Math.max(1, Math.floor(Number(payload && payload.page) || req.page)));
  return { items, page, size, total, totalPages, legacy: false };
}
