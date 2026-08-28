import { Component } from 'react';
import { resetNav } from './menuStore';

// 全局错误边界：捕获任何子组件渲染期异常，避免整页白屏。
// fallback 刻意使用纯 HTML + 内联样式，不依赖 antd，即使 UI 框架自身出错也能显示。
// - 重试：重置错误状态，重新渲染当前页面
// - 恢复默认菜单：清空本地自定义导航（针对保存布局产生的坏数据）
// - 刷新：整页重载
const btn = {
  padding: '6px 14px',
  border: '1px solid #d9d9d9',
  borderRadius: 6,
  background: '#fff',
  cursor: 'pointer',
  fontSize: 13,
};

export default class ErrorBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { error: null, errorInfo: null };
  }

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error, info) {
    // 仅记录真实错误，便于定位（不会展示给用户）
    console.error('[Claw] 渲染异常已被错误边界捕获：', error, info);
    this.setState({ errorInfo: info });
  }

  // 路由切换（resetKey 变化）时自动清除错误，恢复为正常页面
  componentDidUpdate(prevProps) {
    if (this.props.resetKey !== prevProps.resetKey && this.state.error) {
      this.setState({ error: null });
    }
  }

  handleRetry = () => this.setState({ error: null });

  handleResetMenu = () => {
    try { resetNav(); } catch (e) { /* ignore */ }
    this.setState({ error: null });
  };

  render() {
    if (this.state.error) {
      const isDev = typeof import.meta !== 'undefined' && import.meta.env && import.meta.env.DEV;
      return (
        <div style={{ padding: '48px 24px', maxWidth: 880, margin: '0 auto', fontFamily: 'system-ui, -apple-system, sans-serif' }}>
          <div style={{ background: '#fff2f0', border: '1px solid #ffccc7', borderRadius: 8, padding: 20, marginBottom: 16 }}>
            <div style={{ fontSize: 16, fontWeight: 700, color: '#cf1322', marginBottom: 8 }}>⚠️ 页面渲染出现异常</div>
            <div style={{ fontSize: 13, color: '#666', lineHeight: 1.6 }}>
              系统已捕获该错误，未造成数据丢失。可尝试下方操作恢复；若反复出现，请截图错误信息反馈。
            </div>
          </div>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 16 }}>
            <button style={btn} onClick={this.handleRetry}>重试当前页面</button>
            <button style={btn} onClick={this.handleResetMenu}>恢复默认菜单布局</button>
            <button style={btn} onClick={() => window.location.reload()}>刷新整个页面</button>
          </div>
          {isDev && this.state.error && (
            <div style={{ background: '#1e1e1e', color: '#f5f5f5', padding: 16, borderRadius: 8, fontSize: 12, overflow: 'auto' }}>
              <div style={{ color: '#ff4d4f', fontWeight: 700, marginBottom: 8, fontSize: 13 }}>
                {String(this.state.error.message || this.state.error)}
              </div>
              <pre style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                {String(this.state.error.stack || this.state.error)}
              </pre>
              {this.state.errorInfo && this.state.errorInfo.componentStack && (
                <pre style={{ margin: '12px 0 0', whiteSpace: 'pre-wrap', wordBreak: 'break-all', color: '#aaa' }}>
                  {this.state.errorInfo.componentStack}
                </pre>
              )}
            </div>
          )}
          <div style={{ fontSize: 12, color: '#999', marginTop: 12, lineHeight: 1.6 }}>
            提示：若是「保存菜单布局」后出现，通常是本地自定义导航数据异常，点「恢复默认菜单布局」即可立即恢复（仅重置本地导航，不影响业务数据）。
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}
