package io.github.sriharifortitude.camtmatch.camt;

import io.github.sriharifortitude.camtmatch.camt.Statement.Balance;
import io.github.sriharifortitude.camtmatch.camt.Statement.Entry;
import io.github.sriharifortitude.camtmatch.camt.Statement.Transaction;
import io.github.sriharifortitude.camtmatch.core.Iban;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Reads ISO 20022 camt.053 (BankToCustomerStatement), versions .001.02
 * through .001.13. Elements are matched by local name, so the namespace
 * version does not matter; the paths that moved between versions (the
 * counterparty's name under Pty from .001.08, Sts becoming a code element)
 * are tried both ways.
 *
 * <p>Statements arrive from outside. The parser refuses DTDs outright, which
 * rules out XXE and entity-expansion attacks before any entity is seen, and
 * parses with the JDK's secure-processing limits on.
 */
public final class Camt053Parser {

    private static final String NS_PREFIX = "urn:iso:std:iso:20022:tech:xsd:camt.053.";

    public List<Statement> parse(InputStream in) {
        Element root = read(in).getDocumentElement();
        String ns = root.getNamespaceURI();
        if (ns == null || !ns.startsWith(NS_PREFIX)) {
            throw new CamtException("not a camt.053 document (namespace " + ns + ")");
        }
        Element message = child(root, "BkToCstmrStmt").orElseThrow(() -> new CamtException("BkToCstmrStmt missing"));
        List<Statement> statements = new ArrayList<>();
        for (Element stmt : children(message, "Stmt")) statements.add(statement(stmt));
        if (statements.isEmpty()) throw new CamtException("no Stmt element");
        return List.copyOf(statements);
    }

    private static Document read(InputStream in) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(in);
        } catch (SAXException e) {
            throw new CamtException("malformed or disallowed XML: " + e.getMessage(), e);
        } catch (IOException | ParserConfigurationException e) {
            throw new CamtException("could not read document", e);
        }
    }

    private static Statement statement(Element stmt) {
        String id = path(stmt, "Id").orElseThrow(() -> new CamtException("Stmt/Id missing"));
        Element acct = child(stmt, "Acct").orElseThrow(() -> new CamtException("Stmt/Acct missing"));
        Iban account = path(acct, "Id", "IBAN").flatMap(Iban::parse)
                .orElseThrow(() -> new CamtException("statement " + id + ": account IBAN missing or invalid"));
        String currency = path(acct, "Ccy").orElse(null);

        Optional<Balance> opening = Optional.empty();
        Optional<Balance> closing = Optional.empty();
        for (Element bal : children(stmt, "Bal")) {
            String code = path(bal, "Tp", "CdOrPrtry", "Cd").orElse("");
            Balance b = new Balance(signedAmount(bal), date(bal));
            if (code.equals("OPBD") || code.equals("PRCD")) opening = Optional.of(b);
            if (code.equals("CLBD")) closing = Optional.of(b);
        }

        List<Entry> entries = new ArrayList<>();
        int n = 0;
        for (Element ntry : children(stmt, "Ntry")) entries.add(entry(ntry, id + "#" + n++));
        return new Statement(id, account, currency, opening, closing, List.copyOf(entries));
    }

    private static Entry entry(Element ntry, String fallbackRef) {
        String ref = path(ntry, "AcctSvcrRef").or(() -> path(ntry, "NtryRef")).orElse(fallbackRef);
        BigDecimal amount = signedAmount(ntry);
        String currency = child(ntry, "Amt").map(a -> a.getAttribute("Ccy")).orElse("");
        String status = path(ntry, "Sts", "Cd").or(() -> path(ntry, "Sts")).orElse("BOOK");
        LocalDate booking = child(ntry, "BookgDt").map(Camt053Parser::date).orElse(null);
        LocalDate value = child(ntry, "ValDt").map(Camt053Parser::date).orElse(null);

        List<Element> details = new ArrayList<>();
        for (Element nd : children(ntry, "NtryDtls")) details.addAll(children(nd, "TxDtls"));
        List<Transaction> txs = new ArrayList<>();
        if (details.isEmpty()) {
            txs.add(new Transaction(ref, 0, null, amount, currency, null, Optional.empty(), Optional.empty(), List.of()));
        } else {
            BigDecimal single = details.size() == 1 ? amount : null;
            for (int i = 0; i < details.size(); i++) {
                txs.add(transaction(details.get(i), ref, i, single, amount.signum(), currency));
            }
        }
        return new Entry(ref, amount, currency, status, booking, value, List.copyOf(txs));
    }

    private static Transaction transaction(Element tx, String entryRef, int index, BigDecimal entryAmount, int sign, String entryCurrency) {
        // In a batch each TxDtls carries its own amount; for a single one the entry amount is authoritative.
        Optional<Element> amt = child(tx, "Amt").or(() -> pathEl(tx, "AmtDtls", "TxAmt", "Amt"));
        BigDecimal amount = entryAmount != null
                ? entryAmount
                : amt.map(a -> decimal(a).multiply(BigDecimal.valueOf(sign)))
                        .orElseThrow(() -> new CamtException("entry " + entryRef + ": batch transaction " + index + " has no amount"));
        String currency = amt.map(a -> a.getAttribute("Ccy")).filter(s -> !s.isEmpty()).orElse(entryCurrency);

        String e2e = path(tx, "Refs", "EndToEndId").filter(s -> !s.equals("NOTPROVIDED")).orElse(null);
        // The counterparty is the debtor on a credit and the creditor on a debit.
        String party = sign >= 0 ? "Dbtr" : "Cdtr";
        String name = path(tx, "RltdPties", party, "Pty", "Nm").or(() -> path(tx, "RltdPties", party, "Nm")).orElse(null);
        Optional<Iban> iban = path(tx, "RltdPties", party + "Acct", "Id", "IBAN").flatMap(Iban::parse);

        Optional<String> structured = Optional.empty();
        List<String> unstructured = new ArrayList<>();
        Optional<Element> rmt = child(tx, "RmtInf");
        if (rmt.isPresent()) {
            for (Element u : children(rmt.get(), "Ustrd")) unstructured.add(u.getTextContent().trim());
            for (Element s : children(rmt.get(), "Strd")) {
                structured = path(s, "CdtrRefInf", "Ref");
                if (structured.isPresent()) break;
            }
        }
        return new Transaction(entryRef, index, e2e, amount, currency, name, iban, structured, List.copyOf(unstructured));
    }

    private static BigDecimal signedAmount(Element parent) {
        Element amt = child(parent, "Amt").orElseThrow(() -> new CamtException(parent.getLocalName() + "/Amt missing"));
        String ind = path(parent, "CdtDbtInd").orElseThrow(() -> new CamtException(parent.getLocalName() + "/CdtDbtInd missing"));
        return switch (ind) {
            case "CRDT" -> decimal(amt);
            case "DBIT" -> decimal(amt).negate();
            default -> throw new CamtException("CdtDbtInd must be CRDT or DBIT, got " + ind);
        };
    }

    private static BigDecimal decimal(Element amt) {
        try {
            return new BigDecimal(amt.getTextContent().trim());
        } catch (NumberFormatException e) {
            throw new CamtException("not an amount: " + amt.getTextContent());
        }
    }

    private static LocalDate date(Element parent) {
        // Dt, or DtTm whose first ten characters are the date.
        return path(parent, "Dt", "Dt")
                .or(() -> path(parent, "Dt").filter(s -> s.length() == 10))
                .or(() -> path(parent, "Dt", "DtTm").map(s -> s.substring(0, 10)))
                .or(() -> path(parent, "DtTm").map(s -> s.substring(0, 10)))
                .map(LocalDate::parse)
                .orElseThrow(() -> new CamtException(parent.getLocalName() + ": date missing"));
    }

    // -- DOM helpers, by local name ------------------------------------------

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getLocalName())) out.add(e);
        }
        return out;
    }

    private static Optional<Element> child(Element parent, String name) {
        List<Element> c = children(parent, name);
        return c.isEmpty() ? Optional.empty() : Optional.of(c.get(0));
    }

    private static Optional<Element> pathEl(Element start, String... names) {
        Optional<Element> cur = Optional.of(start);
        for (String name : names) cur = cur.flatMap(e -> child(e, name));
        return cur;
    }

    private static Optional<String> path(Element start, String... names) {
        return pathEl(start, names).map(e -> e.getTextContent().trim()).filter(s -> !s.isEmpty());
    }
}
