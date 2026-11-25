import type { Locator, Page } from '@playwright/test';

/** Width of every README image. */
export const FRAME_WIDTH = 1280;
/**
 * Space kept below the last framed block: the minimum page gutter (16 px), less than the page's
 * gap between blocks (24 px), so the image never shows the edge or shadow of the next card.
 */
export const FRAME_MARGIN = 16;

/** Lowest bottom edge of the present `blocks` in page coordinates (px). */
async function bottomOf(blocks: readonly Locator[]): Promise<number> {
  const bottoms: number[] = [];
  for (const block of blocks) {
    if ((await block.count()) > 0) {
      bottoms.push(
        await block.evaluate((element) => element.getBoundingClientRect().bottom + window.scrollY),
      );
    }
  }
  if (bottoms.length === 0) {
    throw new Error('None of the blocks to frame is on the page');
  }
  return Math.ceil(Math.max(...bottoms));
}

/**
 * Resizes the viewport to {@link FRAME_WIDTH} by a height that ends {@link FRAME_MARGIN} px below
 * the lowest of `blocks` (absent ones are skipped), so the frame shows each of them whole and cuts nothing at its bottom
 * edge. The page is scrolled to the top first; the measurement is repeated until the height is
 * stable, because a taller viewport can move blocks whose position depends on it. Returns the
 * height.
 */
export async function frameTo(page: Page, blocks: readonly Locator[]): Promise<number> {
  await page.evaluate(() => window.scrollTo(0, 0));
  let height = page.viewportSize()?.height ?? 0;
  for (let pass = 0; pass < 4; pass += 1) {
    const next = (await bottomOf(blocks)) + FRAME_MARGIN;
    if (next === height) {
      break;
    }
    height = next;
    await page.setViewportSize({ width: FRAME_WIDTH, height });
    await page.evaluate(() => window.scrollTo(0, 0));
  }
  return height;
}
