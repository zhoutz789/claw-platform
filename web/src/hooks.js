import { useState, useEffect, useCallback } from 'react';
import { App } from 'antd';

// 通用取数 hook：fetcher 返回 Promise（已解包到 body.data），自动处理 loading / 错误提示。
export function useFetch(fetcher, deps = []) {
  const { message } = App.useApp();
  const [data, setData] = useState(undefined);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const d = await fetcher();
      setData(d);
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  useEffect(() => {
    load();
  }, [load]);

  return { data, loading, reload: load };
}
