create schema checkout_private;
revoke all on schema checkout_private from public, anon, authenticated;
grant usage on schema checkout_private to service_role;

create table checkout_private.accounts (
    uid text primary key,
    expires_at bigint
);

create table checkout_private.orders (
    id text primary key,
    uid text not null references checkout_private.accounts(uid),
    request_id uuid not null,
    amount integer not null check (amount > 0),
    days integer not null check (days between 1 and 365),
    state text not null check (state in ('CREATING', 'PENDING', 'PAID', 'EXPIRED', 'CANCELED')),
    created_at bigint not null,
    expires_at bigint not null,
    retry_at bigint not null default 0,
    transaction_id text unique,
    qr_url text,
    paid_at bigint
);

create unique index checkout_one_open_order on checkout_private.orders (uid)
    where state in ('CREATING', 'PENDING');

create table checkout_private.requests (
    uid text not null references checkout_private.accounts(uid),
    request_id uuid not null,
    order_id text not null references checkout_private.orders(id),
    primary key (uid, request_id)
);

create table checkout_private.vouchers (
    code_hash text primary key,
    days integer not null check (days between 1 and 365),
    valid_until bigint not null,
    redeemed_uid text
);

create table checkout_private.rate_limits (
    uid text primary key,
    bucket bigint not null,
    requests integer not null
);

alter table checkout_private.accounts enable row level security;
alter table checkout_private.orders enable row level security;
alter table checkout_private.requests enable row level security;
alter table checkout_private.vouchers enable row level security;
alter table checkout_private.rate_limits enable row level security;
grant select, insert, update on all tables in schema checkout_private to service_role;

-- Keep the read, payment transition, and Premium extension in the same Postgres transaction.
create function public.adixtream_checkout_sandbox(operation text, input jsonb)
returns jsonb
language plpgsql
security invoker
set search_path = ''
as $$
declare
    owner_uid text := input->>'uid';
    order_id text := input->>'id';
    request_key uuid := (input->>'request_id')::uuid;
    now_ms bigint := (input->>'now')::bigint;
    order_row checkout_private.orders%rowtype;
    voucher_row checkout_private.vouchers%rowtype;
    resulting_expiry bigint;
    payment_state text := input->>'state';
    gateway_tx text := input->>'transaction_id';
    request_count integer;
begin
    case operation
    when 'by_id' then
        select * into order_row from checkout_private.orders o where o.id = order_id;
        if not found then return null; end if;
        return to_jsonb(order_row);
    when 'open' then
        select * into order_row from checkout_private.orders o
            where o.uid = owner_uid and o.state in ('CREATING', 'PENDING');
        if not found then return null; end if;
        return to_jsonb(order_row);
    when 'reserve' then
        insert into checkout_private.accounts (uid) values (owner_uid) on conflict do nothing;
        perform 1 from checkout_private.accounts a where a.uid = owner_uid for update;
        select * into order_row from checkout_private.orders o
            where o.id = (select r.order_id from checkout_private.requests r
                where r.uid = owner_uid and r.request_id = request_key);
        if found then return to_jsonb(order_row); end if;
        select * into order_row from checkout_private.orders o
            where o.uid = owner_uid and o.state in ('CREATING', 'PENDING');
        if not found then
            insert into checkout_private.orders (id, uid, request_id, amount, days, state, created_at, expires_at)
            values (order_id, owner_uid, request_key, (input->>'amount')::integer,
                (input->>'days')::integer, 'CREATING', (input->>'created_at')::bigint,
                (input->>'expires_at')::bigint)
            returning * into order_row;
        end if;
        insert into checkout_private.requests (uid, request_id, order_id) values (owner_uid, request_key, order_row.id);
        return to_jsonb(order_row);
    when 'claim_charge' then
        update checkout_private.orders o set retry_at = now_ms + 20000
            where o.id = order_id and o.state = 'CREATING' and o.retry_at <= now_ms;
        return to_jsonb(found);
    when 'expire_creation' then
        update checkout_private.orders o set state = 'EXPIRED' where o.id = order_id and o.state = 'CREATING'
            returning * into order_row;
        if not found then select * into order_row from checkout_private.orders o where o.id = order_id; end if;
        return to_jsonb(order_row);
    when 'apply_status' then
        select * into order_row from checkout_private.orders o where o.id = order_id for update;
        if not found or (order_row.transaction_id is not null and order_row.transaction_id <> gateway_tx) then
            raise exception 'GATEWAY_ORDER_MISMATCH';
        end if;
        if order_row.state = 'PAID' or (payment_state <> 'PAID' and order_row.state not in ('CREATING', 'PENDING')) then
            return to_jsonb(order_row);
        end if;
        begin
            update checkout_private.orders o set
                state = payment_state, transaction_id = gateway_tx,
                qr_url = coalesce(input->>'qr_url', o.qr_url),
                paid_at = case when payment_state = 'PAID' then now_ms end
                where o.id = order_id returning * into order_row;
        exception when unique_violation then
            raise exception 'GATEWAY_ORDER_MISMATCH';
        end;
        if payment_state = 'PAID' then
            update checkout_private.accounts a set expires_at =
                greatest(coalesce(a.expires_at, 0), now_ms) + order_row.days::bigint * 86400000
                where a.uid = order_row.uid;
        end if;
        return to_jsonb(order_row);
    when 'entitlement' then
        select a.expires_at into resulting_expiry from checkout_private.accounts a where a.uid = owner_uid;
        return jsonb_build_object('active', coalesce(resulting_expiry, 0) > now_ms, 'expiresAt', resulting_expiry);
    when 'redeem' then
        select * into voucher_row from checkout_private.vouchers v where v.code_hash = input->>'hash' for update;
        if not found then raise exception 'VOUCHER_UNAVAILABLE'; end if;
        if voucher_row.redeemed_uid = owner_uid then
            return public.adixtream_checkout_sandbox('entitlement', input);
        end if;
        if voucher_row.redeemed_uid is not null or voucher_row.valid_until <= now_ms then
            raise exception 'VOUCHER_UNAVAILABLE';
        end if;
        update checkout_private.vouchers v set redeemed_uid = owner_uid where v.code_hash = voucher_row.code_hash;
        insert into checkout_private.accounts as a (uid, expires_at)
            values (owner_uid, now_ms + voucher_row.days::bigint * 86400000)
            on conflict (uid) do update set expires_at =
                greatest(coalesce(a.expires_at, 0), now_ms) + voucher_row.days::bigint * 86400000
            returning a.expires_at into resulting_expiry;
        return jsonb_build_object('active', true, 'expiresAt', resulting_expiry);
    when 'rate_limit' then
        insert into checkout_private.rate_limits as rl (uid, bucket, requests)
            values (owner_uid, now_ms / 60000, 1)
            on conflict (uid) do update set bucket = excluded.bucket,
                requests = case when rl.bucket = excluded.bucket then rl.requests + 1 else 1 end
            returning requests into request_count;
        if request_count > 60 then raise exception 'RATE_LIMIT'; end if;
        return null;
    else
        raise exception 'REQUEST_INVALID';
    end case;
end;
$$;

revoke all on function public.adixtream_checkout_sandbox(text, jsonb) from public, anon, authenticated;
grant execute on function public.adixtream_checkout_sandbox(text, jsonb) to service_role;
