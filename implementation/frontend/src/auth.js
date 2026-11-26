// 登录与注册成功后的数据刷新顺序：先刷新本人私有数据，再刷新公开市场列表。
// 私有刷新失败不回滚登录态；公开市场仍要加载，避免首页跳转后没有商品。
// 顺序固定为一次私有刷新加一次市场刷新，不因重试或弹窗状态产生重复请求。
export async function afterSignIn({ userId, refresh, market }) {
  const result = { privateOk: true, marketOk: true, privateError: null, marketError: null };
  try {
    await refresh(userId);
  } catch (error) {
    result.privateOk = false;
    result.privateError = error;
  }
  try {
    await market();
  } catch (error) {
    result.marketOk = false;
    result.marketError = error;
  }
  return result;
}
