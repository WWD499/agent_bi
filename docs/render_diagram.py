"""将 mermaid 架构图 HTML 截图为 PNG 图片"""
import asyncio
from playwright.async_api import async_playwright

async def render_architecture_diagram():
    html_path = r"D:\个人项目\agent_bi\docs\architecture-diagram.html"
    png_path = r"D:\个人项目\agent_bi\docs\系统架构图.png"

    async with async_playwright() as p:
        browser = await p.chromium.launch()
        page = await browser.new_page(viewport={"width": 1200, "height": 900})
        await page.goto(f"file:///{html_path.replace('\\', '/')}")
        # 等待 mermaid 渲染完成
        await page.wait_for_selector(".mermaid svg", timeout=15000)
        # 获取 SVG 实际尺寸并调整视口
        svg = await page.query_selector(".mermaid svg")
        box = await svg.bounding_box()
        if box:
            await page.set_viewport_size({
                "width": int(box["width"]) + 80,
                "height": int(box["height"]) + 120
            })
        # 截图
        await page.screenshot(path=png_path, full_page=True)
        await browser.close()
    print(f"架构图已保存: {png_path}")

if __name__ == "__main__":
    asyncio.run(render_architecture_diagram())
