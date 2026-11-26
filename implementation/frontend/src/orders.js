// 订单详情视图模型：兼容新 GET /orders/{id}/details 的 {order,delivery,reviews}，
// 以及仅 {order,reviews} 或缺字段的返回；缺失不伪造。
const TERMINAL = ['已完成', '已取消', '已拒绝', '已异常终止'];

export function orderDetails(data) {
  const order = (data && data.order) || null;
  return {
    order,
    delivery: (data && data.delivery) || null,
    reviews: data && Array.isArray(data.reviews) ? data.reviews : [],
  };
}

// 治理详情：争议 d.order_id / 订单类型举报 r.object_id。仅当事人订单才可打开；纯查询。
export function governanceOrderId(kind, row) {
  if (!row) return '';
  if (kind === 'dispute') return row.order_id || '';
  if (kind === 'report') return row.object_type === 'ORDER' ? (row.object_id || '') : '';
  return '';
}

export function isParty(order, me) {
  return !!order && !!me && (order.buyer_id === me.id || order.seller_id === me.id);
}

export function isTerminal(stage) {
  return TERMINAL.includes(stage);
}

// 按 author_id 映射买卖双方，评价附显示名；不认识的作者退化为“交易方”。
export function reviewRows(reviews, order) {
  return (reviews || []).map(r => ({
    ...r,
    authorName: order && r.author_id === order.seller_id ? order.seller_name
      : order && r.author_id === order.buyer_id ? order.buyer_name : '交易方',
    targetName: order && r.author_id === order.seller_id ? order.buyer_name
      : order && r.author_id === order.buyer_id ? order.seller_name : '',
  }));
}

// 本人是否已评：以服务端评价列表为准（含隐藏评价），不依赖仅详情页的本地缓存。
export function myReview(reviews, me) {
  if (!me) return null;
  return (reviews || []).find(r => r.author_id === me.id) || null;
}

// 本人尚无可见评价记录 → 提交入口可以填写；新增成功后由 addReview 置为已评。
export function canReview(order, reviews, me) {
  if (!order || !me) return false;
  if (order.stage !== '已完成') return false;
  return !alreadyReviewed(order, reviews, me);
}

// 本地标记合并：重新打开列表后服务器记录可能延迟，标记仍生效，但不倒填他人。
export function alreadyReviewed(order, reviews, me, marked) {
  if (!me || !order) return false;
  if (myReview(reviews, me)) return true;
  return !!(marked && marked[order.id] === me.id);
}

// 点击评价入口后的处理：已评价只提示；状态未知先加载详情；只有确定未评价才打开表单。
export function reviewIntent(status) {
  if (status === 'done') return 'already';
  if (status === 'unknown') return 'load';
  if (status === 'pending') return 'form';
  return 'ignore';
}

// 评价入口的三种状态：unknown（订单已完成，但评价明细尚未加载）、pending（已加载且本人未评）、
// done（已评价，含本会话提交标记）、not-applicable（未完成订单）。
// “尚未加载”不能当成“尚未评价”，否则刷新后会短暂露出已评订单的评价入口。
export function reviewStatus(order, reviews, me, marked) {
  if (!order || !me) return 'not-applicable';
  if (order.stage !== '已完成') return 'not-applicable';
  if (marked && marked[order.id] === me.id) return 'done';
  if (!Array.isArray(reviews)) return 'unknown';
  return alreadyReviewed(order, reviews, me, marked) ? 'done' : 'pending';
}

export function addReview(reviews, review) {
  const next = (reviews || []).slice();
  if (!next.some(r => r.author_id === review.author_id)) next.push(review);
  return next;
}

// 0/1、true/false 都表示争议受理中；"0"/"1" 也按数值解释，避免字符串 "0" 被当作真值。
function disputed(order) {
  const v = order && order.disputed;
  return v === true || v === 1 || v === '1';
}

// 当前可执行动作，由阶段、争议、当事人身份与卖家身份共同决定。
// 争议受理保留订单原阶段与占用：接单、拒单、登记交付、普通取消仍被拒绝；
// 已登记交付且处于“待买方确认”时，买方本人确认收到仍是该单完成与争议结案的唯一出口（BR19）。
export function actionsFor(order, me) {
  if (!order || !me || !isParty(order, me)) return [];
  const seller = order.seller_id === me.id;
  if (disputed(order)) return order.stage === '待买方确认' && !seller ? ['confirm'] : [];
  if (order.stage === '待卖方确认') return seller ? ['accept', 'reject'] : ['cancel'];
  if (order.stage === '待交付') return seller ? ['deliver', 'cancel'] : ['cancel'];
  if (order.stage === '待买方确认') return seller ? (order.reminded ? [] : ['remind']) : ['confirm'];
  return [];
}

export function showConfirmReceived(order, me) {
  return actionsFor(order, me).includes('confirm');
}

// 新 GET /details 失败（如后端尚未上线）时，仅对已完成订单退避到既有的 /orders/{id}/reviews。
export function fallbackReviews(load, id) {
  return load(id).then(list => ({ order: null, delivery: null, reviews: Array.isArray(list) ? list : [] }));
}
