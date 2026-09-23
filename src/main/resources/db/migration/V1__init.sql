-- Open items from the ledger. The ledger is the system of record; this is
-- the copy the matcher works against, and the place a confirmed match
-- marks an invoice paid.
create table invoices (
    id                 text primary key,
    number             text not null unique,
    amount_due         numeric(18, 2) not null check (amount_due > 0),
    currency           char(3) not null,
    creditor_reference text unique,
    customer_name      text not null,
    customer_iban      text,
    status             text not null default 'open' check (status in ('open', 'paid')),
    paid_by_match      uuid,
    updated_at         timestamptz not null default now()
);

create index invoices_open on invoices (currency, amount_due) where status = 'open';

-- One row per imported statement. (account, statement id) is how a bank
-- identifies a statement; importing the same one twice is refused.
create table statements (
    id            uuid primary key,
    account_iban  text not null,
    statement_id  text not null,
    currency      char(3),
    opening       numeric(18, 2),
    closing       numeric(18, 2),
    -- opening + booked entries - closing. Non-zero: nothing from this
    -- statement is applied automatically.
    discrepancy   numeric(18, 2),
    imported_at   timestamptz not null default now(),
    unique (account_iban, statement_id)
);

create table transactions (
    id                  uuid primary key,
    statement           uuid not null references statements (id) on delete cascade,
    tx_key              text not null,
    amount              numeric(18, 2) not null,
    currency            char(3) not null,
    booking_date        date,
    end_to_end_id       text,
    counterparty_name   text,
    counterparty_iban   text,
    structured_ref      text,
    remittance          text not null default '',
    unique (statement, tx_key)
);

create table matches (
    id          uuid primary key,
    transaction uuid not null unique references transactions (id) on delete cascade,
    outcome     text not null check (outcome in ('MATCHED', 'SUGGESTED', 'UNMATCHED', 'NOT_APPLICABLE')),
    -- proposed: waiting for a person; confirmed: invoices marked paid;
    -- rejected: a person said no; none: nothing to decide.
    state       text not null check (state in ('proposed', 'confirmed', 'rejected', 'none')),
    reasons     text not null,
    decided_at  timestamptz
);

create table match_invoices (
    match   uuid not null references matches (id) on delete cascade,
    invoice text not null references invoices (id),
    primary key (match, invoice)
);

alter table invoices add constraint invoices_paid_by_match foreign key (paid_by_match) references matches (id);
