package com.meetingautomation.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Reusable contract for the hierarchy and health slice of a document provider. */
public abstract class DocumentStructureProviderContractTest {
    protected abstract Supplier<DocumentStructureProvider> structureProviderFactory();

    protected abstract Supplier<DocumentStructureProvider> failingStructureProviderFactory();

    protected String contractRootId() {
        return "root-configured";
    }

    protected String missingContractRootId() {
        return "missing-root-child";
    }

    protected DocumentStructure expectedContractStructure() {
        return new DocumentStructure(contractRootId(), "meetings-child", "participants-child");
    }

    @Test
    protected void validatesConnectionAndDiscoversRequiredDirectChildren() {
        DocumentStructureProvider provider = structureProviderFactory().get();
        assertEquals(new ProviderHealth(true, true, true), provider.validateConnection());
        assertEquals(expectedContractStructure(), provider.discoverStructure(contractRootId()));
    }

    @Test
    protected void incompleteHierarchyUsesStructureNotFoundContract() {
        DocumentProviderException failure = assertThrows(DocumentProviderException.class,
                () -> failingStructureProviderFactory().get().discoverStructure(missingContractRootId()));
        assertEquals("DOCUMENT_STRUCTURE_NOT_FOUND", failure.code());
        assertEquals(422, failure.statusCode());
        assertFalse(failure.retryable());
    }
}
