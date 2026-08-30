// 语言切换器：登录页入口旁（Segmented）+ 后台顶栏常驻（Dropdown）。
//
// 两处复用同一份 LANG_OPTIONS，任何语言下三个选项都显示各自「母语自称」，
// 保证柬籍员工在英文界面下也能一眼认出「ខ្មែរ」。
import { Button, Dropdown, Segmented, Tooltip } from 'antd';
import { TranslationOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { LANG_OPTIONS, normalizeLang } from './index';
import { useLang } from './useLang';

/**
 * 语言切换器。
 *
 * @param {Object} props 组件属性
 * @param {'dropdown'|'segmented'} [props.variant='dropdown'] 形态：顶栏用 dropdown，登录页用 segmented
 * @param {'small'|'middle'|'large'} [props.size='middle'] 尺寸
 * @param {boolean} [props.showText=true] dropdown 形态下是否在按钮上显示当前语言
 * @returns {JSX.Element} 切换器
 */
export default function LangSwitch({ variant = 'dropdown', size = 'middle', showText = true }) {
  const { t } = useTranslation();
  const { lang, changeLang } = useLang();
  const current = normalizeLang(lang);
  const currentOption = LANG_OPTIONS.find((o) => o.value === current) || LANG_OPTIONS[0];

  if (variant === 'segmented') {
    return (
      <Segmented
        value={current}
        size={size}
        onChange={(v) => changeLang(v)}
        options={LANG_OPTIONS.map((o) => ({
          value: o.value,
          // 用母语自称做标签，不随当前语言变化
          label: o.native,
        }))}
        aria-label={t('lang.switch')}
      />
    );
  }

  const items = LANG_OPTIONS.map((o) => ({
    key: o.value,
    label: (
      <span>
        {o.native}
        {o.value === current ? ' ✓' : ''}
      </span>
    ),
  }));

  return (
    <Dropdown
      trigger={['click']}
      placement="bottomRight"
      menu={{
        items,
        selectable: true,
        selectedKeys: [current],
        onClick: ({ key }) => changeLang(key),
      }}
    >
      <Tooltip title={t('lang.switch')}>
        <Button type="text" icon={<TranslationOutlined style={{ fontSize: 18 }} />}>
          {showText ? currentOption.native : null}
        </Button>
      </Tooltip>
    </Dropdown>
  );
}
