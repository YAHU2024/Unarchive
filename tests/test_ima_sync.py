"""
Tests for ima sync module: title/idempotency, quota/rate-limit, partial success.

All tests mock _call to avoid real API requests.
"""
from __future__ import annotations

from unittest.mock import AsyncMock, patch
import pytest

from src.sync.ima import (
    ImaSync,
    CreateDocumentResult,
    ImaQuotaExceededError,
    ImaRateLimitError,
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _make_ima_sync(knowledge_base_id: str = "") -> ImaSync:
    """Create an ImaSync with mock credentials (no real API calls)."""
    return ImaSync(
        client_id="test_client_id",
        api_key="test_api_key",
        knowledge_base_id=knowledge_base_id,
    )


# ---------------------------------------------------------------------------
# 1. Title building and idempotent dedup
# ---------------------------------------------------------------------------

class TestTitleAndDedup:
    """ima note title must contain [video_id] prefix, matching check_document_exists."""

    def test_build_markdown_uses_title_with_prefix(self, sample_card):
        """_build_markdown must use the passed-in title (with [video_id]) as H1."""
        md = ImaSync._build_markdown("[BV123] Some Title", sample_card)
        assert md.startswith("# [BV123] Some Title")

    def test_build_markdown_falls_back_to_card_title(self, sample_card):
        """_build_markdown falls back to card title when passed-in title is empty."""
        md = ImaSync._build_markdown("", sample_card)
        assert md.startswith("# Test Video Title")

    def test_build_markdown_falls_back_to_unknown(self):
        """_build_markdown returns 'unknown' when no title at all."""
        md = ImaSync._build_markdown("", {})
        assert "# 未知标题" in md

    def test_build_markdown_escapes_hash_in_title(self):
        """Titles containing # should be escaped to avoid breaking Markdown."""
        md = ImaSync._build_markdown("[BV123] C# Programming Guide", {})
        assert md.startswith("# [BV123] C&#35; Programming Guide")
        # The unescaped # should NOT appear as an H2
        assert "##" not in md.splitlines()[0]

    @pytest.mark.asyncio
    async def test_check_document_exists_prefix_match(self):
        """check_document_exists returns note_id when title starts with [video_id]."""
        ima = _make_ima_sync()
        mock_data = {
            "search_note_infos": [
                {"note_book_info": {"title": "[BV123] My Video", "note_id": "note_abc"}},
                {"note_book_info": {"title": "BV123 Other Video", "note_id": "note_xyz"}},
            ],
            "is_end": True,
        }
        with patch.object(ima, "_call", AsyncMock(return_value=mock_data)):
            result = await ima.check_document_exists("BV123")
            assert result == "note_abc"

    @pytest.mark.asyncio
    async def test_check_document_exists_no_match(self):
        """check_document_exists returns None when no matching title found."""
        ima = _make_ima_sync()
        mock_data = {
            "search_note_infos": [
                {"note_book_info": {"title": "Some Other Note", "note_id": "note_other"}},
            ],
            "is_end": True,
        }
        with patch.object(ima, "_call", AsyncMock(return_value=mock_data)):
            result = await ima.check_document_exists("BV999")
            assert result is None

    @pytest.mark.asyncio
    async def test_check_document_exists_legacy_prefix_match(self):
        """Legacy match: raw video_id at title start followed by a boundary char."""
        ima = _make_ima_sync()
        mock_data = {
            "search_note_infos": [
                {"note_book_info": {"title": "BV456 - My Old Note", "note_id": "note_def"}},
            ],
            "is_end": True,
        }
        with patch.object(ima, "_call", AsyncMock(return_value=mock_data)):
            result = await ima.check_document_exists("BV456")
            assert result == "note_def"

    @pytest.mark.asyncio
    async def test_check_document_exists_no_boundary_no_match(self):
        """Titles with video_id embedded without boundary do NOT match."""
        ima = _make_ima_sync()
        mock_data = {
            "search_note_infos": [
                {"note_book_info": {"title": "My Note [BV456] copy", "note_id": "note_nope"}},
                {"note_book_info": {"title": "SomeBV456Title", "note_id": "note_nope2"}},
            ],
            "is_end": True,
        }
        with patch.object(ima, "_call", AsyncMock(return_value=mock_data)):
            result = await ima.check_document_exists("BV456")
            assert result is None

    @pytest.mark.asyncio
    async def test_duplicate_sync_idempotent(self):
        """create_document + check_document_exists: second sync finds existing note."""
        ima = _make_ima_sync()
        # check_document_exists uses 2 queries (video_id + [video_id]),
        # each needing an empty search result → 2 calls
        # Then create_document needs 1 call for import_doc → 1 call
        search_none = {"search_note_infos": [], "is_end": True}
        import_data = {"note_id": "note_new_123"}
        call_responses = [search_none, search_none, import_data]

        async def mock_call(path, body, _retry=0):
            return call_responses.pop(0)

        with patch.object(ima, "_call", side_effect=mock_call):
            # First call: should NOT find existing (2 search queries → both empty)
            result1 = await ima.check_document_exists("BV123")
            assert result1 is None
            # Second call: create_document (import_doc)
            result2 = await ima.create_document(
                title="[BV123] Test", content={"title": "Test"}
            )
            assert result2.note_id == "note_new_123"


# ---------------------------------------------------------------------------
# 2. Quota / rate-limit handling
# ---------------------------------------------------------------------------

class TestQuotaAndRateLimit:
    """ImaQuotaExceededError stops sync; ImaRateLimitError retries with backoff."""

    @pytest.mark.asyncio
    async def test_quota_exceeded_propagates(self):
        """create_document raises ImaQuotaExceededError on code 200005."""
        ima = _make_ima_sync(knowledge_base_id="kb_1")

        async def mock_call(path, body, _retry=0):
            raise ImaQuotaExceededError("daily quota exceeded")

        with patch.object(ima, "_call", side_effect=mock_call):
            with pytest.raises(ImaQuotaExceededError):
                await ima.create_document(
                    title="[BV123] Test", content={"title": "Test"}
                )

    @pytest.mark.asyncio
    async def test_rate_limit_retries_then_raises(self):
        """_call retries on HTTP 429 code=200001, then raises ImaRateLimitError."""
        ima = _make_ima_sync()

        call_count = 0

        # Mock the HTTP POST response to simulate rate limiting
        class MockResponse:
            status_code = 429
            @staticmethod
            def json():
                return {"code": 200001, "msg": "rate limited"}

        class MockClient:
            is_closed = False

            async def post(self, url, headers=None, json=None, **kwargs):
                nonlocal call_count
                call_count += 1
                return MockResponse()

            async def aclose(self):
                pass

        with patch.object(ima, "_get_client", return_value=MockClient()), \
             patch("asyncio.sleep", new_callable=AsyncMock):
            with pytest.raises(ImaRateLimitError):
                await ima._call("test/path", {"test": 1})
            # 1 initial + _max_retry retries = _max_retry + 1 total
            assert call_count == ima._max_retry + 1

    @pytest.mark.asyncio
    async def test_quota_stops_create_document(self):
        """Quota error during add_knowledge is propagated from create_document."""
        ima = _make_ima_sync(knowledge_base_id="kb_1")

        # First call: import_doc succeeds
        # Second call: add_knowledge raises quota error
        responses = [
            {"note_id": "note_quota_test"},  # import_doc success
        ]

        async def mock_call(path, body, _retry=0):
            if path == ima.PATH_IMPORT_DOC:
                return responses.pop(0)
            elif path == ima.PATH_ADD_KNOWLEDGE:
                raise ImaQuotaExceededError("daily quota exceeded")
            return {}

        with patch.object(ima, "_call", side_effect=mock_call):
            with pytest.raises(ImaQuotaExceededError):
                await ima.create_document(
                    title="[BV123] Test", content={"title": "Test"},
                    kb_folder_id="folder_abc",
                )


# ---------------------------------------------------------------------------
# 3. Unified business error handling (_handle_business_error + _call)
# ---------------------------------------------------------------------------

class TestBusinessErrorHandling:
    """Unified _handle_business_error works regardless of HTTP status code."""

    # ---- _handle_business_error unit tests (static, no I/O) ----

    def test_code_zero_is_noop(self):
        """code 0 returns silently (success path)."""
        ImaSync._handle_business_error(0, "ok")  # must not raise

    def test_quota_code_200005_raises(self):
        """code 200005 raises ImaQuotaExceededError."""
        with pytest.raises(ImaQuotaExceededError, match="超出配额"):
            ImaSync._handle_business_error(200005, "超出配额")

    def test_rate_limit_code_200001_raises(self):
        """code 200001 raises ImaRateLimitError (retryable, caught by _call)."""
        with pytest.raises(ImaRateLimitError, match="频率超限"):
            ImaSync._handle_business_error(200001, "频率超限")

    def test_other_nonzero_code_raises_runtime_error(self):
        """Arbitrary non-zero codes raise RuntimeError."""
        with pytest.raises(RuntimeError, match="ima API 错误"):
            ImaSync._handle_business_error(100001, "参数错误")

    # ---- _call integration: HTTP 200 with business error codes ----

    @pytest.mark.asyncio
    async def test_call_http200_code_200005_quota(self):
        """_call: HTTP 200 + code 200005 -> ImaQuotaExceededError (no retry)."""
        ima = _make_ima_sync()

        class MockResp:
            status_code = 200

            @staticmethod
            def json():
                return {"code": 200005, "msg": "每日配额已用尽", "data": {}}

        class MockClient:
            is_closed = False

            async def post(self, url, headers=None, json=None, **kwargs):
                return MockResp()

            async def aclose(self):
                pass

        with patch.object(ima, "_get_client", return_value=MockClient()):
            with pytest.raises(ImaQuotaExceededError, match="每日配额"):
                await ima._call("openapi/test/quota", {"test": 1})

    @pytest.mark.asyncio
    async def test_call_http200_code_200001_retry_then_raise(self):
        """_call: HTTP 200 + code 200001 -> exponential backoff -> ImaRateLimitError."""
        ima = _make_ima_sync()
        call_count = 0

        class MockResp:
            status_code = 200

            @staticmethod
            def json():
                return {"code": 200001, "msg": "频率超限，请稍后重试", "data": {}}

        class MockClient:
            is_closed = False

            async def post(self, url, headers=None, json=None, **kwargs):
                nonlocal call_count
                call_count += 1
                return MockResp()

            async def aclose(self):
                pass

        with patch.object(ima, "_get_client", return_value=MockClient()), \
             patch("asyncio.sleep", new_callable=AsyncMock):
            with pytest.raises(ImaRateLimitError, match="频率超限"):
                await ima._call("openapi/test/rate", {"test": 1})
            # initial + _max_retry retries = _max_retry + 1 total
            assert call_count == ima._max_retry + 1

    @pytest.mark.asyncio
    async def test_call_http200_other_nonzero_code(self):
        """_call: HTTP 200 + arbitrary non-zero code -> RuntimeError."""
        ima = _make_ima_sync()

        class MockResp:
            status_code = 200

            @staticmethod
            def json():
                return {"code": 100001, "msg": "参数错误", "data": {}}

        class MockClient:
            is_closed = False

            async def post(self, url, headers=None, json=None, **kwargs):
                return MockResp()

            async def aclose(self):
                pass

        with patch.object(ima, "_get_client", return_value=MockClient()):
            with pytest.raises(RuntimeError, match="ima API 错误"):
                await ima._call("openapi/test/bad", {"test": 1})

    @pytest.mark.asyncio
    async def test_call_non_json_response(self):
        """_call: non-JSON body raises RuntimeError with status info."""
        ima = _make_ima_sync()

        class MockResp:
            status_code = 502

            @staticmethod
            def json():
                raise ValueError("not JSON")

        class MockClient:
            is_closed = False

            async def post(self, url, headers=None, json=None, **kwargs):
                return MockResp()

            async def aclose(self):
                pass

        with patch.object(ima, "_get_client", return_value=MockClient()):
            with pytest.raises(RuntimeError, match="非 JSON"):
                await ima._call("openapi/test/json", {"test": 1})


# ---------------------------------------------------------------------------
# 4. Partial success: note created but kb association failed
# ---------------------------------------------------------------------------

class TestPartialSuccess:
    """create_document returns CreateDocumentResult distinguishing kb status."""

    @pytest.mark.asyncio
    async def test_create_document_no_kb(self):
        """Without knowledge_base_id, result has no kb info (both flags false)."""
        ima = _make_ima_sync()  # no knowledge_base_id
        with patch.object(ima, "_call", AsyncMock(return_value={"note_id": "note_1"})):
            result = await ima.create_document(
                title="[BV123] Test", content={"title": "Test"}
            )
            assert result.note_id == "note_1"
            assert result.kb_added is False
            assert result.kb_error == ""

    @pytest.mark.asyncio
    async def test_create_document_kb_success(self):
        """With knowledge_base_id, kb_added=True on successful add_knowledge."""
        ima = _make_ima_sync(knowledge_base_id="kb_1")
        call_index = 0

        async def mock_call(path, body, _retry=0):
            nonlocal call_index
            call_index += 1
            if call_index == 1:
                return {"note_id": "note_kb_ok"}
            # add_knowledge response
            return {}

        with patch.object(ima, "_call", side_effect=mock_call):
            result = await ima.create_document(
                title="[BV123] Test", content={"title": "Test"},
                kb_folder_id="folder_abc",
            )
            assert result.note_id == "note_kb_ok"
            assert result.kb_added is True
            assert result.kb_error == ""

    @pytest.mark.asyncio
    async def test_create_document_kb_failure(self):
        """kb_added=False, kb_error set when add_knowledge fails (non-quota)."""
        ima = _make_ima_sync(knowledge_base_id="kb_1")
        call_index = 0

        async def mock_call(path, body, _retry=0):
            nonlocal call_index
            call_index += 1
            if call_index == 1:
                return {"note_id": "note_kb_fail"}
            raise RuntimeError("kb association network error")

        with patch.object(ima, "_call", side_effect=mock_call):
            result = await ima.create_document(
                title="[BV123] Test", content={"title": "Test"},
                kb_folder_id="folder_abc",
            )
            assert result.note_id == "note_kb_fail"
            assert result.kb_added is False
            assert "kb association network error" in result.kb_error


# ---------------------------------------------------------------------------
# 5. Two-round sync: kb-association recovery flow
# ---------------------------------------------------------------------------

class TestTwoRoundSync:
    """Round 1: create note + KB fails → Round 2: retry add_knowledge, no re-create.

    This exercises the app-level recovery logic in sync_to_ima(): when
    ima_sync_state records kb_added=False, the next sync finds the existing
    note via check_document_exists and calls add_to_knowledge_base directly
    — without ever hitting import_doc.
    """

    @pytest.mark.asyncio
    async def test_add_to_knowledge_base_standalone(self):
        """Public add_to_knowledge_base works without create_document."""
        ima = _make_ima_sync(knowledge_base_id="kb_1")

        async def mock_call(path, body, _retry=0):
            if path == ima.PATH_ADD_KNOWLEDGE:
                assert body["media_type"] == 11
                assert body["note_info"]["content_id"] == "note_standalone"
                assert body["knowledge_base_id"] == "kb_1"
                return {}
            raise RuntimeError(f"Unexpected path: {path}")

        with patch.object(ima, "_call", side_effect=mock_call):
            # Must not raise
            await ima.add_to_knowledge_base(
                "note_standalone", "[BV123] Standalone Note", "folder_abc"
            )

    @pytest.mark.asyncio
    async def test_two_round_recovery_full(self):
        """Round 1: create_document + KB fails → Round 2: check_document_exists
        finds note, add_to_knowledge_base succeeds, import_doc NOT called."""
        ima = _make_ima_sync(knowledge_base_id="kb_1")

        # ===================== Round 1 =====================
        round1_paths = []

        async def round1_call(path, body, _retry=0):
            round1_paths.append(path)
            if path == ima.PATH_IMPORT_DOC:
                return {"note_id": "note_two_round"}
            if path == ima.PATH_ADD_KNOWLEDGE:
                raise RuntimeError("kb network error in round 1")
            return {}

        with patch.object(ima, "_call", side_effect=round1_call):
            result = await ima.create_document(
                title="[BV123] Recovery Test",
                content={"title": "Test"},
                kb_folder_id="folder_abc",
            )
            assert result.note_id == "note_two_round"
            assert result.kb_added is False
            assert "kb network error in round 1" in result.kb_error

        assert ima.PATH_IMPORT_DOC in round1_paths
        assert ima.PATH_ADD_KNOWLEDGE in round1_paths

        # ===================== Round 2 =====================
        # Simulates a fresh ImaSync instance (same kb_id, new _client)
        ima2 = _make_ima_sync(knowledge_base_id="kb_1")
        round2_paths = []

        async def round2_call(path, body, _retry=0):
            round2_paths.append(path)
            if path == ima.PATH_SEARCH_NOTE:
                return {
                    "search_note_infos": [
                        {
                            "note_book_info": {
                                "title": "[BV123] Recovery Test",
                                "note_id": "note_two_round",
                            }
                        }
                    ],
                    "is_end": True,
                }
            if path == ima.PATH_ADD_KNOWLEDGE:
                assert body["note_info"]["content_id"] == "note_two_round"
                return {}  # success
            # import_doc must not be called in round 2
            raise RuntimeError(f"Unexpected API call in round 2: {path}")

        with patch.object(ima2, "_call", side_effect=round2_call):
            # Step 1: check_document_exists finds existing note
            existing = await ima2.check_document_exists("BV123")
            assert existing == "note_two_round"

            # Step 2: retry add_to_knowledge_base (no import_doc)
            await ima2.add_to_knowledge_base(
                "note_two_round", "[BV123] Recovery Test", "folder_abc"
            )

        # Verify: import_doc was NOT called in round 2
        assert ima.PATH_IMPORT_DOC not in round2_paths
        assert ima.PATH_SEARCH_NOTE in round2_paths
        assert ima.PATH_ADD_KNOWLEDGE in round2_paths

    @pytest.mark.asyncio
    async def test_two_round_recovery_retry_fails_again(self):
        """Round 2 add_to_knowledge_base also fails (non-quota) — error surfaces."""
        ima = _make_ima_sync(knowledge_base_id="kb_1")

        async def mock_call(path, body, _retry=0):
            if path == ima.PATH_SEARCH_NOTE:
                return {
                    "search_note_infos": [
                        {
                            "note_book_info": {
                                "title": "[BV123] Still Failing",
                                "note_id": "note_retry_fail",
                            }
                        }
                    ],
                    "is_end": True,
                }
            if path == ima.PATH_ADD_KNOWLEDGE:
                raise RuntimeError("kb still unavailable in round 2")
            return {}

        with patch.object(ima, "_call", side_effect=mock_call):
            existing = await ima.check_document_exists("BV123")
            assert existing == "note_retry_fail"

            with pytest.raises(RuntimeError, match="kb still unavailable"):
                await ima.add_to_knowledge_base(
                    "note_retry_fail", "[BV123] Still Failing", ""
                )

    @pytest.mark.asyncio
    async def test_two_round_recovery_quota_stops_round2(self):
        """Round 2 add_to_knowledge_base hits quota → ImaQuotaExceededError."""
        ima = _make_ima_sync(knowledge_base_id="kb_1")

        async def mock_call(path, body, _retry=0):
            if path == ima.PATH_SEARCH_NOTE:
                return {
                    "search_note_infos": [
                        {
                            "note_book_info": {
                                "title": "[BV123] Quota Hit",
                                "note_id": "note_quota_round2",
                            }
                        }
                    ],
                    "is_end": True,
                }
            if path == ima.PATH_ADD_KNOWLEDGE:
                raise ImaQuotaExceededError("quota exhausted in round 2")
            return {}

        with patch.object(ima, "_call", side_effect=mock_call):
            existing = await ima.check_document_exists("BV123")
            assert existing == "note_quota_round2"

            with pytest.raises(ImaQuotaExceededError, match="quota exhausted"):
                await ima.add_to_knowledge_base(
                    "note_quota_round2", "[BV123] Quota Hit", ""
                )
