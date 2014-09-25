package nl.topicus.plugins.maven.cachebuster.exception;

public class MatchProcessorException extends RuntimeException
{
	private static final long serialVersionUID = 6825012698692110779L;

	public MatchProcessorException(String message, Throwable cause)
	{
		super(message, cause);
	}
}
