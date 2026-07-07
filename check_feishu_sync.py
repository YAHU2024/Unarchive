"""
check_feishu_sync.py - 只读诊断脚本

列出已同步到飞书『视频知识库』文件夹的所有文档，并打印可点击链接。
本脚本只读取飞书内容，不会创建/修改任何文档或文件夹。

用法:
    python check_feishu_sync.py                  # 默认使用 www 兜底域名
    python check_feishu_sync.py --domain acme    # 直接指定域名 (acme.feishu.cn 的 acme)
"""
import argparse
import asyncio
import io
import sys

# Windows 默认 GBK 控制台下，先确保 stdout/stderr 用 UTF-8，避免中文/符号乱码或崩溃
if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

from config import get_config
from src.sync.feishu import FeishuSync


async def list_synced_docs(domain: str) -> None:
    cfg = get_config()
    if not cfg.feishu_app_id or not cfg.feishu_app_secret:
        print("[!] .env 未配置 FEISHU_APP_ID / FEISHU_APP_SECRET，无法连接飞书。")
        return

    sync = FeishuSync(cfg.feishu_app_id, cfg.feishu_app_secret)
    try:
        if not await sync.connect():
            print("[!] 飞书连接失败，请检查 App ID / App Secret 及开放平台权限。")
            return

        root = await sync.get_root_folder_token()

        # 1) 在根目录下定位『视频知识库』文件夹 (支持分页)
        folder_token = None
        page_token = ""
        while True:
            params = {"folder_token": root, "page_size": 100}
            if page_token:
                params["page_token"] = page_token
            data = await sync._request("GET", "/drive/v1/files", params=params)
            for f in data.get("files", []):
                if f.get("name") == "视频知识库" and f.get("type") == "folder":
                    folder_token = f.get("token")
                    break
            if folder_token or not data.get("has_more"):
                break
            page_token = data.get("page_token", "")
            if not page_token:
                break

        if not folder_token:
            print("[i] 未找到名为『视频知识库』的文件夹 -> 之前应该没有成功同步过飞书。")
            return

        # 2) 列出该文件夹下所有文档 (支持分页)
        docs = []
        page_token = ""
        while True:
            params = {"folder_token": folder_token, "page_size": 100}
            if page_token:
                params["page_token"] = page_token
            data = await sync._request("GET", "/drive/v1/files", params=params)
            for f in data.get("files", []):
                if f.get("type") in ("docx", "doc", "sheet", "bitable", "mindnote"):
                    docs.append(f)
            if not data.get("has_more"):
                break
            page_token = data.get("page_token", "")
            if not page_token:
                break

        if not docs:
            print("[i] 『视频知识库』文件夹为空，尚未同步任何文档。")
            return

        print(f"[ok] 已在飞书『视频知识库』找到 {len(docs)} 个文档：\n")
        for f in docs:
            doc_id = f.get("token")
            url = f"https://{domain}.feishu.cn/docx/{doc_id}"
            print(f"- {f.get('name')}")
            print(f"    {url}")
    finally:
        await sync.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="列出已同步到飞书的文档并打印链接（只读）")
    parser.add_argument(
        "--domain",
        default="www",
        help="飞书租户域名，如 acme.feishu.cn 中的 acme（默认 www 兜底）",
    )
    args = parser.parse_args()
    asyncio.run(list_synced_docs(args.domain))


if __name__ == "__main__":
    main()
