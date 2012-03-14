/**
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 * 
 * An execption is the 1D FFT implementation of Dave Hale which we use as a
 * library, wich is released under the terms of the Common Public License -
 * v1.0, which is available at http://www.eclipse.org/legal/cpl-v10.html  
 *
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 */
package net.imglib2.algorithm.fft;

import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.Benchmark;
import net.imglib2.algorithm.MultiThreaded;
import net.imglib2.algorithm.OutputAlgorithm;
import net.imglib2.algorithm.fft.FourierTransform.PreProcessing;
import net.imglib2.algorithm.fft.FourierTransform.Rearrangement;
import net.imglib2.exception.IncompatibleTypeException;
import net.imglib2.img.Img;
import net.imglib2.img.ImgFactory;
import net.imglib2.iterator.LocalizingZeroMinIntervalIterator;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.complex.ComplexFloatType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;

/**
 * Computes a convolution of an {@link Img} or {@link RandomAccessibleInterval} with an kernel. The computation is based on the Fourier
 * convolution theorem and computation time is therefore independent of the size of kernel (except the kernel becomes bigger than the input,
 * which makes limited sense).
 * 
 * It is possible to exchange the kernel or the image if a series of images is convolved with the same kernel - or if an image has to be convolved
 * with multiple kernels.
 * 
 * The precision of the computation is {@link ComplexFloatType}.
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 *
 * @param <T> - {@link RealType} of the image
 * @param <S> - {@link RealType} of the kernel
 */
public class FourierConvolution<T extends RealType<T>, S extends RealType<S>> implements MultiThreaded, OutputAlgorithm<Img<T>>, Benchmark
{
	final int numDimensions;
	Img<T> convolved;
	RandomAccessibleInterval<T> image;
	RandomAccessibleInterval<S> kernel;
	
	Img<ComplexFloatType> kernelFFT, imgFFT; 
	FourierTransform<T, ComplexFloatType> fftImage;
	final ImgFactory<ComplexFloatType> fftImgFactory;
	final ImgFactory<T> imgFactory;
	final ImgFactory<S> kernelImgFactory;
	
	final int[] kernelDim;

	String errorMessage = "";
	int numThreads;
	long processingTime;

	/**
	 * Computes a convolution in Fourier space.
	 * 
	 * @param image - the input to be convolved
	 * @param kernel - the kernel for the convolution operation
	 * @param fftImgFactory - the {@link ImgFactory} that is used to create the FFT's
	 * @param imgFactory - the {@link ImgFactory} that is used to compute the convolved image
	 * @param kernelImgFactory - the {@link ImgFactory} that is used to extend the kernel to the right size
	 */
	public FourierConvolution( final RandomAccessibleInterval<T> image, final RandomAccessibleInterval<S> kernel,
							   final ImgFactory<T> imgFactory, final ImgFactory<S> kernelImgFactory,
							   final ImgFactory<ComplexFloatType> fftImgFactory )
	{
		this.numDimensions = image.numDimensions();
				
		this.image = image;
		this.kernel = kernel;
		this.fftImgFactory = fftImgFactory;
		this.imgFactory = imgFactory;
		this.kernelImgFactory = kernelImgFactory;
		
		this.kernelDim = new int[ numDimensions ];
		for ( int d = 0; d < numDimensions; ++d )
			kernelDim[ d ] = (int)kernel.dimension( d );

		this.kernelFFT = null;
		this.imgFFT = null;
		
		setNumThreads();
	}
	
	/**
	 * Computes a convolution in Fourier space.
	 * 
	 * @param image - the input {@link Img} to be convolved
	 * @param kernel - the kernel {@link Img} for the convolution operation
	 * @param fftImgFactory - the {@link ImgFactory} that is used to create the FFT's
	 */
	public FourierConvolution( final Img<T> image, final Img<S> kernel, final ImgFactory<ComplexFloatType> fftImgFactory )
	{
		this( image, kernel, image.factory(), kernel.factory(), fftImgFactory );
	}
	
	/**
	 * Computes a convolution in Fourier space.
	 * 
	 * @param image - the input {@link Img} to be convolved
	 * @param kernel - the kernel {@link Img} for the convolution operation
	 * @throws IncompatibleTypeException if the factory of the input {@link Img}<T> is not compatible with the {@link ComplexFloatType} (it needs to be a {@link NativeType})
	 */
	public FourierConvolution( final Img<T> image, final Img<S> kernel ) throws IncompatibleTypeException
	{
		this( image, kernel, image.factory(), kernel.factory(), image.factory().imgFactory( new ComplexFloatType() ) );
	}
	
	/**
	 * @return - the {@link ImgFactory} that is used to create the FFT's
	 */
	public ImgFactory<ComplexFloatType> fftImgFactory() { return fftImgFactory; }

	/**
	 * @return - the {@link ImgFactory} that is used to compute the convolved image
	 */
	public ImgFactory<T> imgFactory() { return imgFactory; }

	public boolean replaceInput( final RandomAccessibleInterval<T> img )
	{
		this.image = img;
		// the fft has to be recomputed
		this.imgFFT = null;
		return true;
	}

	public boolean replaceKernel( final RandomAccessibleInterval<S> knl )
	{
		this.kernel = knl;
		// the fft has to be recomputed
		this.kernelFFT = null;
		return true;
	}

	
	final public static Img<FloatType> createGaussianKernel( final ImgFactory<FloatType> factory, final double sigma, final int numDimensions )
	{
		final double[ ] sigmas = new double[ numDimensions ];
		
		for ( int d = 0; d < numDimensions; ++d )
			sigmas[ d ] = sigma;
		
		return createGaussianKernel( factory, sigmas );
	}

	final public static Img<FloatType> createGaussianKernel( final ImgFactory<FloatType> factory, final double[] sigmas )
	{
		final int numDimensions = sigmas.length;
		
		final int[] imageSize = new int[ numDimensions ];
		final double[][] kernel = new double[ numDimensions ][];
		
		for ( int d = 0; d < numDimensions; ++d )
		{
			kernel[ d ] = Util.createGaussianKernel1DDouble( sigmas[ d ], true );
			imageSize[ d ] = kernel[ d ].length;
		}
		
		final Img<FloatType> kernelImg = factory.create( imageSize, new FloatType() );
		
		final Cursor<FloatType> cursor = kernelImg.localizingCursor();
		final int[] position = new int[ numDimensions ];
		
		while ( cursor.hasNext() )
		{
			cursor.fwd();
			cursor.localize( position );
			
			double value = 1;
			
			for ( int d = 0; d < numDimensions; ++d )
				value *= kernel[ d ][ position[ d ] ];
			
			cursor.get().set( (float)value );
		}
		
		return kernelImg;
	}
	
	@Override
	public boolean process() 
	{		
		final long startTime = System.currentTimeMillis();

		//
		// compute fft of the input image
		//
		if ( imgFFT == null ) //not computed in a previous step
		{
			fftImage = new FourierTransform< T, ComplexFloatType >( image, fftImgFactory, new ComplexFloatType() );
			fftImage.setNumThreads( this.getNumThreads() );
			
			// how to extend the input image out of its boundaries for computing the FFT,
			// we simply mirror the content at the borders
			fftImage.setPreProcessing( PreProcessing.EXTEND_MIRROR );		
			// we do not rearrange the fft quadrants
			fftImage.setRearrangement( Rearrangement.UNCHANGED );
			
			// the image has to be extended by the size of the kernel-1
			// as the kernel is always odd, e.g. if kernel size is 3, we need to add
			// one pixel out of bounds in each dimension (3-1=2 pixel all together) so that the
			// convolution works
			final int[] imageExtension = kernelDim.clone();		
			for ( int d = 0; d < numDimensions; ++d )
				--imageExtension[ d ];		
			fftImage.setImageExtension( imageExtension );
			
			if ( !fftImage.checkInput() || !fftImage.process() )
			{
				errorMessage = "FFT of image failed: " + fftImage.getErrorMessage();
				return false;			
			}
			
			imgFFT = fftImage.getResult();
		}
		
		//
		// create the kernel for fourier transform
		//
		if ( kernelFFT == null )
		{
			// get the size of the kernel image that will be fourier transformed,
			// it has the same size as the image
			final int kernelTemplateDim[] = new int[ numDimensions ];
			for ( int d = 0; d < numDimensions; ++d )
				kernelTemplateDim[ d ] = (int)imgFFT.dimension( d );

			kernelTemplateDim[ 0 ] = ( (int)imgFFT.dimension( 0 ) - 1 ) * 2;
			
			// instaniate real valued kernel template
			// which is of the same container type as the image
			// so that the computation is easy
			final Img<S> kernelTemplate = kernelImgFactory.create( kernelTemplateDim, Util.getTypeFromInterval( kernel ).createVariable() );
			
			// copy the kernel into the kernelTemplate,
			// the key here is that the center pixel of the kernel (e.g. 13,13,13)
			// is located at (0,0,0)
			final RandomAccess<S> kernelCursor = kernel.randomAccess();
			final RandomAccess<S> kernelTemplateCursor = kernelTemplate.randomAccess();
			
			final LocalizingZeroMinIntervalIterator cursorDim = new LocalizingZeroMinIntervalIterator( kernel );
			
			final int[] position = new int[ numDimensions ];
			final int[] position2 = new int[ numDimensions ];
			
			while ( cursorDim.hasNext() )
			{
				cursorDim.fwd();
				cursorDim.localize( position );
				
				for ( int d = 0; d < numDimensions; ++d )
				{
					// the kernel might not be zero-bounded
					position2[ d ] = position[ d ] + (int)kernel.min( d );
					
					position[ d ] = ( position[ d ] - kernelDim[ d ]/2 + kernelTemplateDim[ d ] ) % kernelTemplateDim[ d ];
					/*final int tmp = ( position[ d ] - kernelDim[ d ]/2 );
					
					if ( tmp < 0 )
						position[ d ] = kernelTemplateDim[ d ] + tmp;
					else
						position[ d ] = tmp;*/
				}			
				
				kernelCursor.setPosition( position2 );				
				kernelTemplateCursor.setPosition( position );
				kernelTemplateCursor.get().set( kernelCursor.get() );
			}
			
			// 
			// compute FFT of kernel
			//
			final FourierTransform<S, ComplexFloatType> fftKernel = new FourierTransform<S, ComplexFloatType>( kernelTemplate, fftImgFactory, new ComplexFloatType() );
			fftKernel.setNumThreads( this.getNumThreads() );
			
			fftKernel.setPreProcessing( PreProcessing.NONE );		
			fftKernel.setRearrangement( fftImage.getRearrangement() );
			
			if ( !fftKernel.checkInput() || !fftKernel.process() )
			{
				errorMessage = "FFT of kernel failed: " + fftKernel.getErrorMessage();
				return false;			
			}		
			kernelFFT = fftKernel.getResult();
		}
		
		//
		// Multiply in Fourier Space
		//
		multiply( imgFFT, kernelFFT );
		
		//
		// Compute inverse Fourier Transform
		//		
		final InverseFourierTransform<T, ComplexFloatType> invFFT = new InverseFourierTransform<T, ComplexFloatType>( imgFFT, imgFactory, fftImage );
		invFFT.setNumThreads( this.getNumThreads() );

		if ( !invFFT.checkInput() || !invFFT.process() )
		{
			errorMessage = "InverseFFT of image failed: " + invFFT.getErrorMessage();
			return false;			
		}
		
		convolved = invFFT.getResult();	
		
		processingTime = System.currentTimeMillis() - startTime;
        return true;
	}
	
	/**
	 * Multiply in Fourier Space
	 * 
	 * @param a
	 * @param b
	 */
	protected void multiply( final Img< ComplexFloatType > a, final Img< ComplexFloatType > b )
	{
		final Cursor<ComplexFloatType> cursorA = a.cursor();
		final Cursor<ComplexFloatType> cursorB = b.cursor();
		
		while ( cursorA.hasNext() )
		{
			cursorA.fwd();
			cursorB.fwd();
			
			cursorA.get().mul( cursorB.get() );
		}
	}
	
	@Override
	public long getProcessingTime() { return processingTime; }
	
	@Override
	public void setNumThreads() { this.numThreads = Runtime.getRuntime().availableProcessors(); }

	@Override
	public void setNumThreads( final int numThreads ) { this.numThreads = numThreads; }

	@Override
	public int getNumThreads() { return numThreads; }	

	@Override
	public Img<T> getResult() { return convolved; }

	@Override
	public boolean checkInput() 
	{
		if ( errorMessage.length() > 0 )
		{
			return false;
		}
		
		if ( image == null )
		{
			errorMessage = "Input image is null";
			return false;
		}
		
		if ( kernel == null )
		{
			errorMessage = "Kernel image is null";
			return false;
		}
		
		for ( int d = 0; d < numDimensions; ++d )
			if ( kernel.dimension( d ) % 2 != 1)
			{
				errorMessage = "Kernel image has NO odd dimensionality in dim " + d + " (" + kernel.dimension( d ) + ")";
				return false;
			}
		
		return true;
	}

	@Override
	public String getErrorMessage()  { return errorMessage; }

}