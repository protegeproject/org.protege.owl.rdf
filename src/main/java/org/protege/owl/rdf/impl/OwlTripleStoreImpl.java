package org.protege.owl.rdf.impl;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.UUID;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.rdfxml.RDFXMLWriter;
import org.protege.owl.rdf.api.OwlTripleStore;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.rdf.rdfxml.parser.OWLRDFConsumer;
import org.semanticweb.owlapi.rdf.rdfxml.parser.RDFConsumer;
import org.semanticweb.owlapi.util.AnonymousNodeChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

public class OwlTripleStoreImpl implements OwlTripleStore {
	public static final Logger LOGGER = LoggerFactory.getLogger(OwlTripleStoreImpl.class);
	
	public static final String NS = "http://protege.org/owl2triplestore.owl";
	public static final String HASH_CODE = NS + "#hashCode";
	public static final String SOURCE_ONTOLOGY = NS + "#sourceOntology";
	public static final String ONTOLOGY_ID = NS + "#ontologyId";
	public static final String ONTOLOGY_VERSION = NS + "#ontologyVersion";
	
	public static final String BNODE_PREFIX = "_:BNode";
	
	
	private org.eclipse.rdf4j.model.IRI hashCodeProperty;
	private org.eclipse.rdf4j.model.IRI sourceOntologyProperty;
	private org.eclipse.rdf4j.model.IRI ontologyIdProperty;
	private org.eclipse.rdf4j.model.IRI ontologyVersionProperty;
	
	private Repository repository;
	private AnonymousResourceHandler anonymousHandler;

	private AnonymousNodeChecker anonymousNodeChecker = new AnonymousNodeChecker() {
        @Override
        public boolean isAnonymousNode(org.semanticweb.owlapi.model.IRI iri) {
            return iri.toString().startsWith(BNODE_PREFIX);
        }

        @Override
        public boolean isAnonymousSharedNode(String iri) {
            return false;
        }

        @Override
        public boolean isAnonymousNode(String iri) {
            return false;
        }
    };
	
	
	public OwlTripleStoreImpl(Repository repository, OWLDataFactory factory) {
		this.repository = repository;
		ValueFactory rdfFactory = repository.getValueFactory();
		hashCodeProperty        = rdfFactory.createIRI(HASH_CODE);
		sourceOntologyProperty  = rdfFactory.createIRI(SOURCE_ONTOLOGY);
		ontologyIdProperty      = rdfFactory.createIRI(ONTOLOGY_ID);
		ontologyVersionProperty = rdfFactory.createIRI(ONTOLOGY_VERSION);
		anonymousHandler = new AnonymousResourceHandler(factory);
	}

	@Override
	public Repository getRepository() {
		return repository;
	}

	@Override
	public void addAxiom(OWLOntologyID ontologyId, OWLAxiom axiom) throws RepositoryException {
	    axiom = anonymousHandler.insertSurrogates(axiom);
	    IRI ontologyRepresentative = getOntologyRepresentative(ontologyId);
		if (getAxiomId(ontologyId, axiom) != null) {
			return;
		}
		RDFTranslator.translate(repository, axiom, hashCodeProperty, sourceOntologyProperty, ontologyRepresentative);
	}

	@Override
	public void removeAxiom(OWLOntologyID ontologyId, OWLAxiom axiom) throws RepositoryException {
	    axiom = anonymousHandler.insertSurrogates(axiom);
		IRI axiomResource = getAxiomId(ontologyId, axiom);
		if (axiomResource != null) {
		    removeAxiom(axiomResource);
		}
	}
	
	@Override
    public boolean hasAxiom(OWLOntologyID ontologyId, OWLAxiom axiom) throws RepositoryException {
	    axiom = anonymousHandler.insertSurrogates(axiom);
		return getAxiomId(ontologyId, axiom) != null;
	}
	
	@Override
	public CloseableIteration<OWLAxiom, RepositoryException> listAxioms(OWLOntologyID ontologyId) throws RepositoryException {
		IRI ontologyRepresentative = getOntologyRepresentative(ontologyId);
	    final RepositoryConnection connection = repository.getConnection();
		boolean success = false;
		try {
			final RepositoryResult<Statement> stmts = connection.getStatements(null, sourceOntologyProperty, ontologyRepresentative, false);
			CloseableIteration<OWLAxiom, RepositoryException> it = new CloseableIteration<OWLAxiom, RepositoryException>() {

				@Override
				public boolean hasNext() throws RepositoryException {
					return stmts.hasNext();
				}

				@Override
				public OWLAxiom next() throws RepositoryException {
					Statement stmt = stmts.next();
					IRI axiomResource = (IRI) stmt.getSubject();
					RepositoryConnection connection = repository.getConnection();
					try {
						return anonymousHandler.removeSurrogates(parseAxiom(connection, axiomResource));
					}
					catch (RepositoryException re) {
						throw re;
					}
					catch (Exception e) {
						throw new RepositoryException(e);
					}
					finally {
						connection.close();
					}
				}

				@Override
				public void remove() throws RepositoryException {
					stmts.remove();
				}

				@Override
				public void close() throws RepositoryException {
					stmts.close();
				}
				
			};
			success = true;
			return it;
		}
		finally {
			if (!success) {
				connection.close();
			}
		}
	}
	
	@Override
	public boolean integrityCheck() {
		throw new UnsupportedOperationException("Not supported yet");
	}
	
	@Override
	public boolean incorporateExternalChanges() {
		throw new UnsupportedOperationException("Not supported yet");
	}

	/**
	 * Get the URL which names the graph representing an axiom.
	 * <p/>
	 * One of the questions that has caused trouble with this function in the past is whether it should expect the axiom 
	 * with the surrogates for anonymous individuals or without.  I have decided that it will expect the axiomn with the surrogates
	 * but this is a relatively arbitrary decision.
	 * 
	 * @param ontologyId
	 * @param axiom
	 * @return
	 * @throws RepositoryException
	 */
	private IRI getAxiomId(OWLOntologyID ontologyId, OWLAxiom axiom) throws RepositoryException {
	    IRI ontologyRepresentative = getOntologyRepresentative(ontologyId);
		ValueFactory factory = repository.getValueFactory();
		RepositoryConnection connection = repository.getConnection();
		try {
			Literal hashCodeValue = factory.createLiteral(axiom.hashCode());
			RepositoryResult<Statement> correctHashCodes = connection.getStatements(null, hashCodeProperty, hashCodeValue, false);
			try {
			    while (correctHashCodes.hasNext()) {
			        Statement stmt = correctHashCodes.next();
			        if (stmt.getSubject() instanceof IRI) {
			            IRI axiomId = (IRI) stmt.getSubject();
			            if (connection.hasStatement(axiomId, sourceOntologyProperty, ontologyRepresentative, false)
			                    && axiom.equals(parseAxiom(connection, axiomId))) {
			                return axiomId;
			            }
			        }
			    }
			}
            finally {
                correctHashCodes.close();
            }
			return null;
		}
		catch (Exception ooce) {
			throw new RepositoryException(ooce);
		}
		finally {
			connection.close();
		}
	}
	
	/**
	 * Uses the connection to parse the axiom with a given axiomId.
	 * <p/>
	 * One of the things that has caused confusion in the past about this method is whether it 
	 * should return the axiom with or without its surrogates for anonymous individual.
	 * I have decided that it would return the version of the axiom with the surrogates (as it is stored in the rdf triple store.)
	 * 
	 * 
	 * @param connection
	 * @param axiomId
	 * @return
	 * @throws OWLOntologyCreationException
	 * @throws RepositoryException
	 * @throws SAXException
	 * @throws IOException
	 * @throws RDFHandlerException
	 */
	private OWLAxiom parseAxiom(RepositoryConnection connection, IRI axiomId) throws OWLOntologyCreationException, RepositoryException, SAXException, IOException, RDFHandlerException {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Starting parse");
        }

		OWLRDFConsumer consumer = consumeTriples(connection, axiomId);
		consumer.endModel();
		OWLOntology ontology = consumer.getOntology();
		OWLAxiom result = null;
		if (ontology.getAxiomCount() == 1) {
			result= ontology.getAxioms().iterator().next();
		}
		else if (ontology.getAxiomCount() > 1) {
			for (OWLAxiom axiom : ontology.getAxioms()) {
				if (!(axiom instanceof OWLDeclarationAxiom)) {
					result = axiom;
					break;
				}
			}
		}
		return result;
	}
	
	@Override
    public OWLClassExpression parseClassExpression(BNode classExpressionNode) throws RepositoryException {
		RepositoryConnection connection = repository.getConnection();
		try {
			RepositoryResult<Statement> triples = connection.getStatements(classExpressionNode, null, null, false);
			Statement stmt = triples.next();
			IRI axiomId = (IRI) stmt.getContext();
			OWLRDFConsumer consumer = consumeTriples(connection, axiomId);
			String nodeName = generateName(classExpressionNode);
			OWLClassExpression ce = consumer.translateClassExpression(org.semanticweb.owlapi.model.IRI.create(nodeName));
			consumer.endModel();
			if (!((TrackingOntologyFormat) consumer.getOntologyFormat()).getFailed()) {
				return ce;
			}
			else {
				return null;
			}
		}
		catch (Exception e) {
			if (e instanceof RepositoryException) {
				throw (RepositoryException) e;
			}
			else {
				throw new RepositoryException(e);
			}
		}
		finally {
			connection.close();
		}
	}
	
	private OWLRDFConsumer consumeTriples(RepositoryConnection connection, IRI axiomId) throws OWLOntologyCreationException, RepositoryException, IOException, RDFHandlerException, SAXException {
		OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
		OWLOntology ontology = manager.createOntology();
		OWLRDFConsumer consumer = new OWLRDFConsumer(ontology, anonymousNodeChecker, new OWLOntologyLoaderConfiguration());
		consumer.setOntologyFormat(new TrackingOntologyFormat());
		RepositoryResult<Statement> triples = connection.getStatements(null, null, null, false, axiomId);
		try {
		    RDFWriter writer = null;
		    if (LOGGER.isDebugEnabled()) {
		        File tmp = File.createTempFile("owl2triples", ".owl");
		        writer = new RDFXMLWriter(new FileWriter(tmp));
		        LOGGER.debug("Writing to " + tmp);
		        writer.startRDF();
		    }
		    while (triples.hasNext()) {
		        Statement stmt = triples.next();
		        if (LOGGER.isDebugEnabled()) {
		            LOGGER.debug(stmt.toString());
		            writer.handleStatement(stmt);
		        }
		        String subjectName = generateName(stmt.getSubject());
		        String predicateName = generateName(stmt.getPredicate());
		        if (stmt.getObject() instanceof Literal) {
		            addTriple(consumer, subjectName, predicateName, (Literal) stmt.getObject());
		        } else {
		            addTriple(consumer, subjectName, predicateName, (Resource) stmt.getObject());
		        }
		    }
	        if (LOGGER.isDebugEnabled()) {
	            writer.endRDF();
	            LOGGER.debug("Parse complete - " + ontology.getAxioms());
	        }
		}
		finally {
		    triples.close();
		}
		return consumer;
	}
	
    private void addTriple(RDFConsumer consumer,
			               String subjectName, String predicateName, Literal literal) throws SAXException {
		String datatype;
		if (literal.getDatatype() == null) {
			datatype = null; // OWL2Datatype.RDF_PLAIN_LITERAL.getIRI().toString();
		}
		else {
			datatype = literal.getDatatype().stringValue();
		}
		consumer.statementWithLiteralValue(subjectName, 
				                           predicateName, 
				                           literal.stringValue(), 
				                           literal.getLanguage().orElse(null), 
				                           datatype);
	}
	
    private void addTriple(RDFConsumer consumer,
                           String subjectName, 
                           String predicateName, 
                           Resource value) throws SAXException {
		consumer.statementWithResourceValue(subjectName, predicateName, generateName(value));
	}
	
	private void removeAxiom(IRI axiomResource) throws RepositoryException {
	    if (axiomResource == null) {
	        return;
	    }
		RepositoryConnection connection = repository.getConnection();
		RepositoryResult<Statement> stmts = null;
		try {
			stmts = connection.getStatements(null, null, null, false, axiomResource);
			connection.remove(stmts, axiomResource);
		}
		finally {
		    if (stmts != null) {
		        stmts.close();
		    }
			connection.close();
		}
	}
	
	private String generateName(Resource resource) {
	    String name;
	    if (resource instanceof BNode) {
	        name = BNODE_PREFIX + ((BNode) resource).getID();
	    }
	    else {
	        name = resource.stringValue();
	    }
	    return name;
	}

	
	private IRI getOntologyRepresentative(OWLOntologyID id) throws RepositoryException {
	    if (id.isAnonymous()) {
	        return repository.getValueFactory().createIRI(anonymousHandler.getSurrogateId(id).toString());
	    }
	    else {
	        return getNamedOntologyRepresentative(id);
	    }
	}
	
	private IRI getNamedOntologyRepresentative(OWLOntologyID id) throws RepositoryException {
        IRI result = null;
        RepositoryConnection connection = repository.getConnection();
        try {
            IRI rdfId = repository.getValueFactory().createIRI(id.getOntologyIRI().toString());
            IRI rdfVersion = id.getVersionIRI().isPresent() ? repository
                    .getValueFactory().createIRI(
                            id.getVersionIRI().get().toString()) : null;
            RepositoryResult<Statement> idStatements = connection.getStatements(null, ontologyIdProperty, rdfId, false);
            try {
                while (idStatements.hasNext()) {
                    Statement idStatement = idStatements.next();
                    IRI possible = (IRI) idStatement.getSubject();
                    RepositoryResult<Statement> versionStatements = connection.getStatements(possible, ontologyVersionProperty, null, false);
                    try {
                        if (rdfVersion == null && !versionStatements.hasNext()) {
                            result = possible;
                            break;
                        }
                        else {
                            while (versionStatements.hasNext()) {
                                Statement versionStatement = versionStatements.next();
                                if (versionStatement.getObject().equals(rdfVersion)) {
                                    result = possible;
                                    break;
                                }
                            }
                            if (result != null) {
                                break;
                            }
                        }
                    }
                    finally {
                        versionStatements.close();
                    }
                }
            }
            finally {
                idStatements.close();
            }
            if (result == null) {
                result = createNamedOntologyRepresentative(rdfId, rdfVersion);
            }
        }
        finally {
            connection.close();
        }
        return result;
	}

	private IRI createNamedOntologyRepresentative(IRI rdfId, IRI rdfVersion) throws RepositoryException {
	    String uriString = NS + "#" + UUID.randomUUID().toString().replaceAll("-", "_");
	    IRI representative = repository.getValueFactory().createIRI(uriString);
	    RepositoryConnection connection = repository.getConnection();
	    try {
	       connection.add(representative, ontologyIdProperty, rdfId);
	       if (rdfVersion != null) {
	           connection.add(representative, ontologyVersionProperty, rdfVersion);
	       }
	    }
	    finally {
	        connection.close();
	    }
	    return representative;
	}

}
