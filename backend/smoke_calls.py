import json
import os
import urllib.request
import uuid

BASE = os.environ.get("MOVNET_SMOKE_BASE_URL", "http://127.0.0.1:8000").rstrip("/")


def request(path, method="GET", token=None, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=15) as response:
        raw = response.read()
        return json.loads(raw) if raw else {}


def main():
    suffix = uuid.uuid4().hex[:12]
    password = "SmokeTest-Only-Password-123!"
    first = request("/auth/register", "POST", body={
        "email": "call-smoke-a-" + suffix + "@example.com",
        "password": password,
        "display_name": "Call Smoke A",
    })
    second = request("/auth/register", "POST", body={
        "email": "call-smoke-b-" + suffix + "@example.com",
        "password": password,
        "display_name": "Call Smoke B",
    })
    token_a, token_b = first["access_token"], second["access_token"]
    public_b = request("/social/me", token=token_b)["public_id"]
    request("/social/friend-requests", "POST", token_a, {"target_public_id": public_b})
    incoming_requests = request("/social/friend-requests", token=token_b)
    request_id = incoming_requests[0]["request_id"]
    request("/social/friend-requests/" + str(request_id) + "/accept", "POST", token_b, {})
    friend_b = request("/social/friends", token=token_a)[0]["user_id"]
    created = request("/social/calls", "POST", token_a, {
        "recipient_user_id": friend_b, "media_type": "audio"
    })
    call_id = created["call_id"]
    calls = request("/social/calls/incoming", token=token_b)
    assert any(item["call_id"] == call_id for item in calls), "Incoming call not listed"
    request("/social/calls/" + str(call_id) + "/status", "PUT", token_b, {"status": "accepted"})
    request("/social/calls/" + str(call_id) + "/signals", "POST", token_a, {
        "kind": "offer", "payload": json.dumps({"type": "offer", "sdp": "smoke-sdp"})
    })
    signals = request("/social/calls/" + str(call_id) + "/signals", token=token_b)
    assert any(item["kind"] == "offer" for item in signals), "WebRTC offer not delivered"
    ice = request("/social/calls/ice-servers", token=token_a)
    assert ice.get("ice_servers"), "ICE server config is empty"
    request("/social/calls/" + str(call_id) + "/status", "PUT", token_a, {"status": "ended"})
    print("MOVNET direct-call API smoke test passed")


if __name__ == "__main__":
    main()
