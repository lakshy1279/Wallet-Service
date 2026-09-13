#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# Wallet Service — Burst Concurrency Tests
# Usage: ./burst_test.sh [BASE_URL]
# Requires: curl, jq
# ─────────────────────────────────────────────────────────────
set -uo pipefail

BASE_URL="${1:-http://localhost:8080}"
FAILED=0
RUN_ID="$(date +%s)"
TMPDIR=$(mktemp -d)

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { echo -e "${GREEN}✓ PASS:${NC} $1"; }
fail() { echo -e "${RED}✗ FAIL:${NC} $1"; FAILED=1; }
info() { echo -e "${YELLOW}→${NC} $1"; }

cleanup() { rm -rf "$TMPDIR"; }
trap cleanup EXIT

echo ""
echo "══════════════════════════════════════════════════════"
echo "  Wallet Service Burst Tests  (run=$RUN_ID)"
echo "  Target: $BASE_URL"
echo "══════════════════════════════════════════════════════"
echo ""

# ─────────────────────────────────────────────────────────────
# TEST 1: Concurrent Get-or-Create
# Fire 20 simultaneous POST /wallets for the same user.
# Expect: exactly one wallet (same ID in all responses).
# ─────────────────────────────────────────────────────────────
echo "━━━ TEST 1: Concurrent Get-or-Create (20 requests) ━━━"
TOKEN_1="user-burst-$RUN_ID"
info "Token: $TOKEN_1"

for i in $(seq 1 20); do
  curl -s -X POST "$BASE_URL/wallets" \
    -H "Authorization: Bearer $TOKEN_1" \
    -H "Content-Type: application/json" \
    > "$TMPDIR/wallet_$i.json" 2>/dev/null &
done
wait

WALLET_IDS=$(cat "$TMPDIR"/wallet_*.json | jq -r '.id // empty' | sort -u)
COUNT=$(echo "$WALLET_IDS" | grep -c . || true)

if [ "$COUNT" -eq 1 ]; then
  pass "All 20 requests returned the same wallet ID: $WALLET_IDS"
else
  fail "Got $COUNT unique wallet IDs (expected 1)"
  echo "  IDs: $WALLET_IDS"
fi

echo ""

# ─────────────────────────────────────────────────────────────
# TEST 2: Idempotent Retry Storm
# Create two wallets, deposit funds, then fire 20 concurrent
# transfers with the SAME idempotency_key.
# Expect: exactly one debit, all responses identical.
# ─────────────────────────────────────────────────────────────
echo "━━━ TEST 2: Idempotent Retry Storm (20 requests) ━━━"

SENDER_TOKEN="sender-$RUN_ID"
RECEIVER_TOKEN="receiver-$RUN_ID"

# Create wallets
SENDER_WALLET=$(curl -s -X POST "$BASE_URL/wallets" \
  -H "Authorization: Bearer $SENDER_TOKEN" \
  -H "Content-Type: application/json")
SENDER_ID=$(echo "$SENDER_WALLET" | jq -r '.id')
info "Sender wallet: $SENDER_ID"

RECEIVER_WALLET=$(curl -s -X POST "$BASE_URL/wallets" \
  -H "Authorization: Bearer $RECEIVER_TOKEN" \
  -H "Content-Type: application/json")
RECEIVER_ID=$(echo "$RECEIVER_WALLET" | jq -r '.id')
info "Receiver wallet: $RECEIVER_ID"

# Deposit ₹1000 (100000 paise) into sender
sleep 0.2
curl -s -X POST "$BASE_URL/wallets/$SENDER_ID/deposit" \
  -H "Authorization: Bearer $SENDER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"amount_paise": 100000}' > /dev/null

IDEM_KEY="idem-storm-$RUN_ID"
TRANSFER_AMOUNT=5000

# Fire 20 concurrent transfers with same key
for i in $(seq 1 20); do
  curl -s -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer $SENDER_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"from\": \"$SENDER_ID\", \"to\": \"$RECEIVER_ID\", \"amount_paise\": $TRANSFER_AMOUNT, \"idempotency_key\": \"$IDEM_KEY\"}" \
    > "$TMPDIR/idem_$i.json" 2>/dev/null &
done
wait

TRANSFER_IDS=$(cat "$TMPDIR"/idem_*.json | jq -r '.id // empty' | sort -u)
TRANSFER_COUNT=$(echo "$TRANSFER_IDS" | grep -c . || true)

if [ "$TRANSFER_COUNT" -eq 1 ]; then
  pass "All 20 requests returned the same transfer ID: $TRANSFER_IDS"
else
  fail "Got $TRANSFER_COUNT unique transfer IDs (expected 1)"
fi

# Verify only one debit occurred
sleep 0.2
SENDER_BALANCE=$(curl -s "$BASE_URL/wallets/$SENDER_ID" \
  -H "Authorization: Bearer $SENDER_TOKEN" | jq -r '.balance_paise')
EXPECTED_BALANCE=$((100000 - TRANSFER_AMOUNT))

if [ "$SENDER_BALANCE" -eq "$EXPECTED_BALANCE" ]; then
  pass "Sender debited exactly once: balance=$SENDER_BALANCE (expected $EXPECTED_BALANCE)"
else
  fail "Sender balance=$SENDER_BALANCE (expected $EXPECTED_BALANCE) — possible double debit!"
fi

# Verify all responses are identical status
STATUSES=$(cat "$TMPDIR"/idem_*.json | jq -r '.status // empty' | sort -u)
STATUS_COUNT=$(echo "$STATUSES" | grep -c . || true)
if [ "$STATUS_COUNT" -eq 1 ] && [ "$STATUSES" = "COMPLETED" ]; then
  pass "All responses have status=COMPLETED"
else
  fail "Inconsistent statuses: $STATUSES"
fi

echo ""

# ─────────────────────────────────────────────────────────────
# TEST 3: Conservation Under Contention
# Create 4 wallets, deposit ₹1000 each (total ₹4000 = 400000 paise).
# Fire 50 concurrent transfers among them. After completion,
# total balance must be unchanged and no balance negative.
# ─────────────────────────────────────────────────────────────
echo "━━━ TEST 3: Conservation Under Contention (50 transfers) ━━━"

declare -a W_TOKENS W_IDS
for i in 1 2 3 4; do
  W_TOKENS[$i]="cuser-$i-$RUN_ID"
  WJSON=$(curl -s -X POST "$BASE_URL/wallets" \
    -H "Authorization: Bearer ${W_TOKENS[$i]}" \
    -H "Content-Type: application/json")
  W_IDS[$i]=$(echo "$WJSON" | jq -r '.id')
  info "Wallet $i: ${W_IDS[$i]} (token: ${W_TOKENS[$i]})"
done

# Deposit 100000 paise into each
sleep 0.2
for i in 1 2 3 4; do
  curl -s -X POST "$BASE_URL/wallets/${W_IDS[$i]}/deposit" \
    -H "Authorization: Bearer ${W_TOKENS[$i]}" \
    -H "Content-Type: application/json" \
    -d '{"amount_paise": 100000}' > /dev/null
done

INITIAL_TOTAL=400000
info "Initial total balance: $INITIAL_TOTAL paise"

# Define transfer pairs: (from_index, to_index)
PAIRS=(
  "1:2" "2:3" "3:4" "4:1"
  "1:3" "3:1" "2:4" "4:2"
  "1:4" "4:1" "2:1" "3:2"
  "4:3" "2:1" "1:2" "3:4"
)
AMOUNT=1000

# Fire 50 concurrent transfers
for i in $(seq 1 50); do
  PAIR_IDX=$(( (i - 1) % ${#PAIRS[@]} ))
  PAIR="${PAIRS[$PAIR_IDX]}"
  FROM_IDX="${PAIR%%:*}"
  TO_IDX="${PAIR##*:}"
  KEY="conserve-$RUN_ID-$i"

  curl -s -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer ${W_TOKENS[$FROM_IDX]}" \
    -H "Content-Type: application/json" \
    -d "{\"from\": \"${W_IDS[$FROM_IDX]}\", \"to\": \"${W_IDS[$TO_IDX]}\", \"amount_paise\": $AMOUNT, \"idempotency_key\": \"$KEY\"}" \
    > "$TMPDIR/conserve_$i.json" 2>/dev/null &
done
wait

sleep 0.3

# Check balances
TOTAL=0
ALL_NON_NEGATIVE=true
echo ""
info "Final balances:"
for i in 1 2 3 4; do
  BAL=$(curl -s "$BASE_URL/wallets/${W_IDS[$i]}" \
    -H "Authorization: Bearer ${W_TOKENS[$i]}" | jq -r '.balance_paise')
  echo "  Wallet $i (${W_IDS[$i]}): $BAL paise"
  TOTAL=$((TOTAL + BAL))
  if [ "$BAL" -lt 0 ]; then
    ALL_NON_NEGATIVE=false
  fi
done

echo ""
if [ "$TOTAL" -eq "$INITIAL_TOTAL" ]; then
  pass "Total balance conserved: $TOTAL paise (expected $INITIAL_TOTAL)"
else
  fail "Total balance = $TOTAL paise (expected $INITIAL_TOTAL) — MONEY CREATED OR DESTROYED!"
fi

if $ALL_NON_NEGATIVE; then
  pass "No wallet has a negative balance"
else
  fail "At least one wallet has a negative balance — OVERDRAFT DETECTED!"
fi

# Count completed vs declined
COMPLETED=$(cat "$TMPDIR"/conserve_*.json | jq -r '.status // empty' | grep -c "COMPLETED" || true)
DECLINED=$(cat "$TMPDIR"/conserve_*.json | jq -r '.status // empty' | grep -c "DECLINED" || true)
info "Transfers: $COMPLETED completed, $DECLINED declined (insufficient funds)"

echo ""
echo "══════════════════════════════════════════════════════"
if [ "$FAILED" -eq 0 ]; then
  echo -e "  ${GREEN}ALL TESTS PASSED${NC}"
else
  echo -e "  ${RED}SOME TESTS FAILED${NC}"
fi
echo "══════════════════════════════════════════════════════"
echo ""

exit $FAILED
