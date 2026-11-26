export const stages=['待卖方确认','待交付','待买方确认'];
export const categories=['教材书籍','数码配件','宿舍生活','运动户外','其他'];
export const isActive=o=>stages.includes(o.stage);
export function actions(o,user){if(!user)return [];const seller=o.seller_id===user.id;if(o.stage==='待买方确认')return seller?(o.reminded?[]:['remind']):['confirm'];if(o.disputed)return [];if(o.stage==='待卖方确认')return seller?['accept','reject']:['cancel'];if(o.stage==='待交付')return seller?['deliver','cancel']:['cancel'];return [];}
export const labels={accept:'接单',reject:'拒单',deliver:'登记交付',confirm:'确认收到',cancel:'取消订单',remind:'提醒买方'};
export function validatePassword(password){return [...password].length>=8&&new TextEncoder().encode(password).length<=72&&password.trim().length>0;}
