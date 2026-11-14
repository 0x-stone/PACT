import unittest
from unittest.mock import patch, MagicMock
import urllib.error
import time
from dao import near_rpc

class TestNearRpcGateway(unittest.TestCase):
    def setUp(self):
        near_rpc._VIEW_CACHE.clear()

    @patch("dao.near_rpc.urllib.request.urlopen")
    def test_view_cache_deduplication(self, mock_urlopen):
        mock_resp = MagicMock()
        mock_resp.read.return_value = b'{"jsonrpc": "2.0", "result": {"result": [49]}}' # '1'
        mock_resp.__enter__.return_value = mock_resp
        mock_urlopen.return_value = mock_resp

        # First call fetches from network
        res1 = near_rpc.view("pact-sandbox.testnet", "get_min_members", {})
        self.assertEqual(res1, 1)
        self.assertEqual(mock_urlopen.call_count, 1)

        # Second call within TTL returns cached value without network hit
        res2 = near_rpc.view("pact-sandbox.testnet", "get_min_members", {})
        self.assertEqual(res2, 1)
        self.assertEqual(mock_urlopen.call_count, 1)

        # Bypass cache forces network fetch
        res3 = near_rpc.view("pact-sandbox.testnet", "get_min_members", {}, bypass_cache=True)
        self.assertEqual(res3, 1)
        self.assertEqual(mock_urlopen.call_count, 2)

    @patch("dao.near_rpc.time.sleep")
    @patch("dao.near_rpc.urllib.request.urlopen")
    def test_429_retry_after_handling(self, mock_urlopen, mock_sleep):
        # First call returns 429 with Retry-After: 2
        err_429 = urllib.error.HTTPError("http://test", 429, "Too Many Requests", {"Retry-After": "2"}, None)
        mock_resp = MagicMock()
        mock_resp.read.return_value = b'{"jsonrpc": "2.0", "result": {"result": [49, 48, 48]}}' # '100'
        mock_resp.__enter__.return_value = mock_resp
        mock_urlopen.side_effect = [err_429, mock_resp]

        res = near_rpc.view("pact-sandbox.testnet", "get_quorum_pct", {})
        self.assertEqual(res, 100)
        mock_sleep.assert_called_with(2.0)

if __name__ == "__main__":
    unittest.main()
