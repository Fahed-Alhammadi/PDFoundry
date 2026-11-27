// The reader's floating bar. While reading it offers three modes and Undo:
//   Markup  Fill & Sign  Pages   Undo
// A mode swaps the bar for its own tools, with a close button on the left and Undo at the right end:
//   Markup:      ×  Highlight▾  Underline  Strikethrough  Text  Draw  Shapes▾  Comment  Redact   Undo
//   Fill & Sign: ×  Signature  Initials  Text  Date  ✓  ✗   Undo
// Pages is not a mode: it opens the page organizer. The same names and grouping as the phone apps.
import { h, icon } from './dom.ts';
import type { Rgba, ShapeKind, TextMark } from '../core/pdf.ts';

export type ToolMode = 'none' | 'markup' | 'fillSign';

export const MODE_NAMES: Record<Exclude<ToolMode, 'none'>, string> = { markup: 'Markup', fillSign: 'Fill & Sign' };

/** The highlight colours and opacities of the iPhone app (systemYellow, systemGreen, systemPink, systemBlue). */
export const HIGHLIGHT_COLOURS: { name: string; rgba: Rgba; css: string }[] = [
  { name: 'Yellow', rgba: [1, 0.8, 0, 0.5], css: '#ffcc00' },
  { name: 'Green', rgba: [0.204, 0.78, 0.349, 0.4], css: '#34c759' },
  { name: 'Pink', rgba: [1, 0.176, 0.333, 0.4], css: '#ff2d55' },
  { name: 'Blue', rgba: [0, 0.478, 1, 0.35], css: '#007aff' },
];
/** Underline and strikethrough: systemRed, as on the iPhone. */
export const MARK_RED: Rgba = [1, 0.231, 0.188, 1];

/** Shapes: the four highlight colours at full strength, and the brand graphite (the default). */
export const SHAPE_COLOURS: { name: string; rgba: Rgba; css: string }[] = [
  { name: 'Graphite', rgba: [0x12 / 255, 0x14 / 255, 0x1c / 255, 1], css: '#12141c' },
  ...HIGHLIGHT_COLOURS.map((c) => ({ name: c.name, rgba: [c.rgba[0], c.rgba[1], c.rgba[2], 1] as Rgba, css: c.css })),
];
export const SHAPE_NAMES: Record<ShapeKind, string> = { rectangle: 'Rectangle', ellipse: 'Ellipse', line: 'Line', arrow: 'Arrow' };

/** A text tool waiting for (or applied to) a selection. */
export type TextTool = { mark: TextMark; rgba: Rgba };
/** A shape tool: each drag on a page draws one. */
export type ShapeTool = { kind: ShapeKind; rgba: Rgba };
/** What a Markup tool is waiting for on the page, if anything. */
export type Armed = { text: TextTool | null; shape: ShapeTool | null; comment: boolean };

export type ModeBarActions = {
  setMode: (mode: ToolMode) => void;
  highlight: (anchor: HTMLElement) => void;
  textMark: (mark: Exclude<TextMark, 'highlight'>) => void;
  text: () => void;
  draw: () => void;
  shapes: (anchor: HTMLElement) => void;
  comment: () => void;
  signature: () => void;
  initials: () => void;
  date: () => void;
  mark: (mark: 'check' | 'cross') => void;
  pages: () => void;
  undo: () => void;
};

function tool(name: string, label: string, onclick: (e: MouseEvent) => void, options: { disabled?: boolean; pressed?: boolean; menu?: boolean; title?: string } = {}): HTMLButtonElement {
  return h('button.tool', {
    type: 'button', disabled: options.disabled, onclick, title: options.title,
    'aria-pressed': options.pressed === undefined ? undefined : String(options.pressed),
    'aria-haspopup': options.menu ? 'menu' : undefined,
  }, icon(name, 22), h('span.tool-label', label, options.menu ? icon('caret', 12) : null));
}

/** The buttons of the bar for `mode`. `armed` is the text tool waiting for a selection, if any. */
export function modeBar(mode: ToolMode, state: { canUndo: boolean; armed: Armed }, actions: ModeBarActions): HTMLElement[] {
  const undo = [h('span.dock-divider'), tool('undo', 'Undo', () => actions.undo(), { disabled: !state.canUndo })];
  if (mode === 'none') {
    return [
      tool('markup', 'Markup', () => actions.setMode('markup')),
      tool('sign', 'Fill & Sign', () => actions.setMode('fillSign')),
      tool('pages', 'Pages', () => actions.pages()),
      ...undo,
    ];
  }
  const close = h('button.tool.close-mode', {
    type: 'button', title: `Close ${MODE_NAMES[mode]}`, 'aria-label': `Close ${MODE_NAMES[mode]}`, onclick: () => actions.setMode('none'),
  }, icon('close', 20));
  if (mode === 'markup') {
    const armed = state.armed.text?.mark;
    const highlight = tool('highlight', 'Highlight', () => actions.highlight(highlight), { pressed: armed === 'highlight', menu: true });
    const shapes = tool('shapes', 'Shapes', () => actions.shapes(shapes), { pressed: !!state.armed.shape, menu: true });
    return [
      close,
      highlight,
      tool('underline', 'Underline', () => actions.textMark('underline'), { pressed: armed === 'underline' }),
      tool('strike', 'Strikethrough', () => actions.textMark('strikethrough'), { pressed: armed === 'strikethrough' }),
      tool('text', 'Text', () => actions.text()),
      tool('draw', 'Draw', () => actions.draw()),
      shapes,
      tool('comment', 'Comment', () => actions.comment(), { pressed: state.armed.comment }),
      ...undo,
    ];
  }
  return [
    close,
    tool('sign', 'Signature', () => actions.signature()),
    tool('initials', 'Initials', () => actions.initials()),
    tool('text', 'Text', () => actions.text()),
    tool('date', 'Date', () => actions.date(), { title: 'Today’s date' }),
    tool('check', 'Check', () => actions.mark('check'), { title: 'Check mark (✓)' }),
    tool('cross', 'Cross', () => actions.mark('cross'), { title: 'Cross (✗)' }),
    ...undo,
  ];
}
