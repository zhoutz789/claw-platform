/**
 * 全球化运营 / 法域（jurisdiction）域。
 *
 * 共营框架的顶层维度：国家主表 + 身份/支付/牌照适配器注册表 + 运营主体 + 分润规则。
 * 核心业务域（user/asset/ledger/order...）保持法域无关，本域按 country_code 挂载各国合规适配，
 * 进一国只需插数据，不动核心代码。本域可依赖 common 共享内核，不得反向依赖业务域。
 */
package com.claw.server.domain.jurisdiction;
