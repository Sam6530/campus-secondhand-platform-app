-- 先在页面注册本人用于联调的账户，再由数据库维护人执行；不内置账号或密码。
-- 将下方用户名替换为刚注册的账户，执行后重新登录。
SET @admin_username = 'REPLACE_WITH_REGISTERED_USERNAME';
UPDATE accounts SET role = 'ADMIN' WHERE username = @admin_username;
SELECT public_ref, username, role FROM accounts WHERE username = @admin_username;
